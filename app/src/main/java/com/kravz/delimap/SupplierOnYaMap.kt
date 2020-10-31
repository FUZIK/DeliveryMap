package com.kravz.delimap

import android.content.Context
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.geometry.Geo
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.geometry.SubpolylineHelper
import com.yandex.mapkit.map.*
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.transport.masstransit.*
import com.yandex.runtime.Error
import com.yandex.runtime.image.ImageProvider
import timber.log.Timber
import android.os.Handler
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.directions.driving.DrivingOptions
import com.yandex.mapkit.directions.driving.DrivingRoute
import com.yandex.mapkit.directions.driving.DrivingSession
import com.yandex.mapkit.directions.driving.VehicleOptions
import kotlinx.coroutines.*
import com.kravz.delimap.R

// https://www.shodor.org/stella2java/rgbint.html

class SupplierOnYaMap(
    private val context: Context,
    private val map: Map
) {
    private val mapObjects = map.mapObjects.addCollection()
    private var supplierMarker: PlacemarkMapObject? = null
    private var routePolyObjects = ArrayList<PolylineMapObject>()
    private val imageProvider = ImageProvider.fromResource(context, R.drawable.car_marker)
    val drivingRouteListener = object : DrivingSession.DrivingRouteListener {
        override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
            if (routes.isNotEmpty()) {
                val firstRoute = routes[0]
                for (section in firstRoute.sections) {
                    val polyline = SubpolylineHelper.subpolyline(firstRoute.geometry, section.geometry)
                    routePolyObjects.add(drawSection(polyline))
                }
            }
            moveAnimate()
        }

        override fun onDrivingRoutesError(error: Error) {
            Timber.w(error.javaClass.name)
        }
    }
    private var animateMarkerJob: Job? = null

    private fun drawSection(polyline: Polyline): PolylineMapObject {
        val polylineMapObject = mapObjects.addPolyline(polyline)
        polylineMapObject.strokeColor = -7498389
        return polylineMapObject
    }

    private fun clearPrevRouteData() {
        routePolyObjects.forEach {
            mapObjects.remove(it)
        }
        routePolyObjects.clear()
        supplierMarker?.let { supplierMarker ->
            mapObjects.remove(supplierMarker as MapObject)
            this.supplierMarker = null
        }
        animateMarkerJob?.apply {
            cancel("Job canceled by clearPrevRouteData")
        }
    }

    private fun initRoute(from: Point, to: Point) {
        supplierMarker = mapObjects.addPlacemark(from, imageProvider).apply {
            isVisible = false
        }
        doGenerateAndDrawRoute(from, to)
    }

    private fun doGenerateAndDrawRoute(from: Point, to: Point) {
        val masstransitRouter = DirectionsFactory.getInstance().createDrivingRouter()
        val masstransitOptions = MasstransitOptions(arrayListOf(), arrayListOf(), TimeOptions())
        val vehicleOptions = VehicleOptions()
        val points = listOf(
            RequestPoint(from, RequestPointType.WAYPOINT, null),
            RequestPoint(to, RequestPointType.WAYPOINT, null)
        )
        masstransitRouter.requestRoutes(points, DrivingOptions(), vehicleOptions, drivingRouteListener)
    }

    fun moveAnimate() {
        supplierMarker?.let { supplierMarker ->
            val routePoints = mutableListOf<Point>()
            routePolyObjects.forEach {
                routePoints.addAll(it.geometry.points)
            }
            val handler = Handler(context.mainLooper)
            var lastPoint = supplierMarker.geometry
            supplierMarker.isVisible = true
            animateMarkerJob = GlobalScope.launch {
                for (nextPoint in routePoints) {
                    val distance = Geo.distance(lastPoint, nextPoint)
                    val time = distance / 8
                    handler.post {
                        PointAnimationUtils.animateMarker(
                            nextPoint,
                            supplierMarker,
                            time.toLong() * 1000
                        )
                    }
                    delay(time.toLong() * 1000)
                    lastPoint = nextPoint
                }
            }
        }
    }

    fun startMove(from: Point, to: Point) {
        if (supplierMarker != null) {
            clearPrevRouteData()
        }
        initRoute(from, to)
    }

    fun hide(hide: Boolean) {
        for (poly in routePolyObjects) {
            poly.isVisible = !(poly.isVisible && hide)
        }
        supplierMarker?.let { supplierMarker ->
            supplierMarker.isVisible = !(supplierMarker.isVisible && hide)
        }
    }
}
