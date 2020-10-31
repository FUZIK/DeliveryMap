package com.kravz.delimap

import com.yandex.mapkit.geometry.Geo
import com.yandex.mapkit.geometry.Point
import kotlin.math.round
import kotlin.random.Random

class PointFactory {
    companion object {
        const val MIN_DISTANCE_KM = 30

        fun roundForCords(d: Double) =
            round(d * 1000000) / 1000000

        fun randomPointInMKAD(): Point {
            var lat: Double
            var lon: Double
            do {
                lat = Random.nextDouble(MKAD.MIN_X, MKAD.MAX_X)
                lon = Random.nextDouble(MKAD.MIN_Y, MKAD.MAX_Y)
            } while (!MKAD.inMKAD(lat, lon))
            return Point(lat, lon)
        }

        fun randomRouteInMKAD(): List<Point> {
            val startPoint = randomPointInMKAD()
            var endPoint = randomPointInMKAD()
            while (Geo.distance(startPoint, endPoint) <= MIN_DISTANCE_KM) {
                endPoint = randomPointInMKAD()
            }
            return listOf(startPoint, endPoint)
        }
    }
}