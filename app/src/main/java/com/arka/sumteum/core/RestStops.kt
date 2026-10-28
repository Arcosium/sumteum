package com.arka.sumteum.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RestStop(val name: String, val kind: String, val lat: Double, val lon: Double)

/** Rest stops and drowsy-driving shelters (OpenStreetMap, ODbL) bundled as CSV: name,kind,lat,lon. */
class RestStops(private val stops: List<RestStop>) {

    /** Nearest stop ahead of the car (within 60 degrees of heading) and its straight-line distance in km. */
    fun nextAhead(lat: Double, lon: Double, heading: Double, minKm: Double = 0.3, maxKm: Double = 40.0): Pair<RestStop, Double>? =
        stops.asSequence()
            .filter { servesHeading(it.name, it.lat, it.lon, heading) }
            .map { it to km(lat, lon, it.lat, it.lon) }
            .filter { (s, d) -> d in minKm..maxKm && angleDiff(bearing(lat, lon, s.lat, s.lon), heading) <= 60 }
            .minByOrNull { it.second }

    /**
     * Nearest stop along a known route ([path] = points ahead, first is the car): within 600 m of the
     * road, distance measured along the road. Used whenever the route is known.
     */
    fun nextOnRoute(path: List<Pair<Double, Double>>, heading: Double, minKm: Double = 0.3): Pair<RestStop, Double>? {
        if (path.size < 2) return null
        val along = DoubleArray(path.size)
        for (i in 1 until path.size) along[i] = along[i - 1] + km(path[i - 1].first, path[i - 1].second, path[i].first, path[i].second)
        val (lat, lon) = path[0]
        return stops.asSequence()
            .filter { km(lat, lon, it.lat, it.lon) <= along.last() + 1 && servesHeading(it.name, it.lat, it.lon, heading) }
            .mapNotNull { s ->
                val i = path.indices.minBy { km(s.lat, s.lon, path[it].first, path[it].second) }
                if (km(s.lat, s.lon, path[i].first, path[i].second) <= 0.6 && along[i] >= minKm) s to along[i] else null
            }
            .minByOrNull { it.second }
    }

    val size get() = stops.size

    companion object {
        fun parse(csv: String) = RestStops(csv.lineSequence().drop(1).mapNotNull { line ->
            val c = line.split(',')
            if (c.size < 4) null else RestStop(c[0], c[1], c[2].toDouble(), c[3].toDouble())
        }.toList())

        fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
            return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
        }

        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        private fun angleDiff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)

        // Expressway facilities name the city they lead to: "죽전휴게소 (서울방향)", "안성휴게소(부산방면)".
        private val DIRECTION = Regex("([가-힣]+)\\s*방[향면]")
        private val CITIES = mapOf(
            "서울" to (37.56 to 126.98), "부산" to (35.18 to 129.08), "인천" to (37.46 to 126.71),
            "대전" to (36.35 to 127.38), "대구" to (35.87 to 128.60), "광주" to (35.16 to 126.85),
            "울산" to (35.54 to 129.31), "세종" to (36.48 to 127.29), "목포" to (34.81 to 126.39),
            "강릉" to (37.75 to 128.88), "춘천" to (37.88 to 127.73), "원주" to (37.34 to 127.92),
            "속초" to (38.21 to 128.59), "양양" to (38.08 to 128.62), "삼척" to (37.45 to 129.17),
            "동해" to (37.52 to 129.11), "통영" to (34.85 to 128.43), "순천" to (34.95 to 127.49),
            "여수" to (34.76 to 127.66), "포항" to (36.02 to 129.34), "전주" to (35.82 to 127.15),
            "청주" to (36.64 to 127.49), "천안" to (36.81 to 127.11), "평택" to (36.99 to 127.11),
            "당진" to (36.89 to 126.63), "창원" to (35.23 to 128.68), "마산" to (35.21 to 128.57),
            "진주" to (35.18 to 128.11), "논산" to (36.19 to 127.10), "공주" to (36.45 to 127.12),
            "충주" to (36.99 to 127.93), "제천" to (37.13 to 128.19), "안동" to (36.57 to 128.73),
            "영덕" to (36.42 to 129.37), "하남" to (37.54 to 127.21), "구리" to (37.59 to 127.13),
            "일산" to (37.66 to 126.77), "판교" to (37.39 to 127.11), "익산" to (35.95 to 126.96),
        )

        /** False when the name says the facility serves traffic toward a city behind us. Unknown names pass. */
        fun servesHeading(name: String, lat: Double, lon: Double, heading: Double): Boolean {
            val city = CITIES[DIRECTION.find(name)?.groupValues?.get(1)] ?: return true
            return angleDiff(bearing(lat, lon, city.first, city.second), heading) <= 90
        }
    }
}
