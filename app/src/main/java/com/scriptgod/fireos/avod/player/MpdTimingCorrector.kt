package com.scriptgod.fireos.avod.player

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Corrects DASH MPD timing by converting SegmentList to SegmentBase.
 *
 * Amazon's MPD uses SegmentList with a fixed `duration` attribute, but actual segment
 * durations vary. Over 2+ hours, the cumulative drift can exceed 40 seconds.
 *
 * Fix: replace SegmentList with SegmentBase pointing to the sidx box in each content
 * file. ExoPlayer natively reads sidx and uses accurate per-segment timing.
 */
object MpdTimingCorrector {

    private const val TAG = "MpdTimingCorrector"

    suspend fun correctMpd(mpdUrl: String, httpClient: OkHttpClient): String {
        Log.w(TAG, "Downloading MPD...")
        val mpdXml = downloadString(mpdUrl, httpClient)
        val mpdBaseUrl = mpdUrl.substringBeforeLast('/') + "/"

        return try {
            val doc = parseXml(mpdXml)
            val rolesInjected = injectDescriptiveAudioRoles(doc)

            if (mpdXml.contains("SegmentList") && mpdXml.contains("duration=\"")) {
                rewriteSegmentListToBase(doc, mpdBaseUrl, httpClient)
            } else if (rolesInjected) {
                serializeXml(doc)
            } else {
                Log.w(TAG, "No SegmentList or roles to inject, returning original")
                mpdXml
            }
        } catch (e: Exception) {
            Log.e(TAG, "MPD correction failed: ${e.message}", e)
            mpdXml
        }
    }

    /**
     * Injects <Role schemeIdUri="urn:mpeg:dash:role:2011" value="description"/> into every
     * AdaptationSet whose audioTrackId contains "_descriptive" (Amazon's authoritative AD marker).
     * ExoPlayer maps value="description" to ROLE_FLAG_DESCRIBES_VIDEO, which our audio menu
     * uses for reliable AD detection without needing string heuristics.
     */
    private fun injectDescriptiveAudioRoles(doc: Document): Boolean {
        val adaptationSets = doc.getElementsByTagName("AdaptationSet")
        var injected = false
        for (i in 0 until adaptationSets.length) {
            val adaptationSet = adaptationSets.item(i) as? Element ?: continue
            val audioTrackId = adaptationSet.getAttribute("audioTrackId")
            if (!audioTrackId.contains("_descriptive", ignoreCase = true)) continue

            // Skip if Role=description already present
            val children = adaptationSet.childNodes
            val alreadyHasRole = (0 until children.length).any { j ->
                val child = children.item(j)
                child is Element && child.localName == "Role" &&
                    child.getAttribute("value") == "description"
            }
            if (alreadyHasRole) continue

            val role = doc.createElement("Role")
            role.setAttribute("schemeIdUri", "urn:mpeg:dash:role:2011")
            role.setAttribute("value", "description")
            val firstChild = adaptationSet.firstChild
            if (firstChild != null) adaptationSet.insertBefore(role, firstChild)
            else adaptationSet.appendChild(role)
            injected = true
            Log.w(TAG, "Injected Role=description for audioTrackId=$audioTrackId")
        }
        return injected
    }

    private suspend fun rewriteSegmentListToBase(
        doc: Document,
        mpdBaseUrl: String,
        httpClient: OkHttpClient
    ): String {
        val mpd = doc.documentElement

        // Probe sidx from ONE file to get the size. All representations with the same
        // segment count (2087) have the same sidx size (25076 bytes).
        val sidxSize = probeFirstSidx(doc, mpdBaseUrl, httpClient)
        if (sidxSize <= 0) {
            Log.w(TAG, "No sidx found, returning serialized (possibly role-injected) MPD")
            return serializeXml(doc)
        }

        // Convert all SegmentList -> SegmentBase and make BaseURLs absolute
        // (the corrected MPD is saved locally, so relative BaseURLs would break).
        val representations = doc.getElementsByTagName("Representation")
        var correctedCount = 0

        for (i in 0 until representations.length) {
            val rep = representations.item(i) as? Element ?: continue
            val segList = getChildElement(rep, "SegmentList") ?: continue
            if (segList.getAttribute("duration").isNullOrEmpty()) continue

            val init = getChildElement(segList, "Initialization") ?: continue
            val initRange = init.getAttribute("range") ?: continue
            val initEnd = initRange.split("-").lastOrNull()?.toLongOrNull() ?: continue

            // Make the Representation's BaseURL absolute
            val baseUrlEl = getChildElement(rep, "BaseURL")
            if (baseUrlEl != null) {
                val url = baseUrlEl.textContent ?: ""
                if (!url.startsWith("http")) {
                    baseUrlEl.textContent = mpdBaseUrl + url
                }
            }

            val indexRangeStart = initEnd + 1
            val indexRangeEnd = indexRangeStart + sidxSize - 1

            val segBase = doc.createElement("SegmentBase")
            segBase.setAttribute("indexRange", "$indexRangeStart-$indexRangeEnd")
            val newInit = doc.createElement("Initialization")
            newInit.setAttribute("range", initRange)
            segBase.appendChild(newInit)

            rep.replaceChild(segBase, segList)
            correctedCount++
        }

        if (correctedCount == 0) {
            Log.w(TAG, "No representations corrected")
            return serializeXml(doc)
        }

        val profiles = mpd.getAttribute("profiles") ?: ""
        if (!profiles.contains("on-demand")) {
            mpd.setAttribute("profiles", "urn:mpeg:dash:profile:isoff-on-demand:2011")
        }

        val result = serializeXml(doc)
        Log.w(TAG, "Corrected $correctedCount representations (SegmentList -> SegmentBase, sidx=$sidxSize)")
        return result
    }

    /**
     * Probe exactly ONE representation to find the sidx size.
     * Returns the box size, or -1 if not found.
     */
    private fun probeFirstSidx(doc: Document, mpdBaseUrl: String, httpClient: OkHttpClient): Int {
        val representations = doc.getElementsByTagName("Representation")
        for (i in 0 until representations.length) {
            val rep = representations.item(i) as? Element ?: continue
            val segList = getChildElement(rep, "SegmentList") ?: continue
            if (segList.getAttribute("duration").isNullOrEmpty()) continue

            val init = getChildElement(segList, "Initialization") ?: continue
            val initRange = init.getAttribute("range") ?: continue
            val initEnd = initRange.split("-").lastOrNull()?.toLongOrNull() ?: continue

            val baseUrlEl = getChildElement(rep, "BaseURL")
            val relativeUrl = baseUrlEl?.textContent ?: continue
            val fileUrl = if (relativeUrl.startsWith("http")) relativeUrl else mpdBaseUrl + relativeUrl

            val size = probeSidxSize(fileUrl, initEnd, httpClient)
            if (size != null && size > 0) {
                Log.w(TAG, "Probed sidx size=$size from ${fileUrl.substringAfterLast('/')}")
                return size
            }
        }
        return -1
    }

    private fun probeSidxSize(fileUrl: String, initEnd: Long, httpClient: OkHttpClient): Int? {
        val start = initEnd + 1
        val request = Request.Builder()
            .url(fileUrl)
            .header("Range", "bytes=$start-${start + 7}")
            .build()

        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val header = resp.body?.bytes() ?: return null
                if (header.size < 8) return null

                val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                val boxSize = buf.int
                val boxType = String(header, 4, 4)

                if (boxType != "sidx") {
                    Log.w(TAG, "Expected sidx, found '$boxType' in ${fileUrl.substringAfterLast('/')}")
                    return null
                }
                boxSize
            }
        } catch (e: Exception) {
            Log.e(TAG, "sidx probe failed: ${e.message}")
            null
        }
    }

    private fun getChildElement(parent: Element, tagName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.localName == tagName) return child
        }
        return null
    }

    private fun parseXml(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun serializeXml(doc: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "no")
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "no")
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    private fun downloadString(url: String, httpClient: OkHttpClient): String {
        val request = Request.Builder().url(url).build()
        return httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
            resp.body?.string() ?: throw RuntimeException("Empty body for $url")
        }
    }
}
