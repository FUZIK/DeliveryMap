package com.kravz.delimap

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.geometry.SubpolylineHelper
import com.yandex.mapkit.location.Location
import com.yandex.mapkit.location.LocationListener
import com.yandex.mapkit.location.LocationStatus
import com.yandex.mapkit.map.*
import com.yandex.mapkit.map.Map
import com.yandex.mapkit.search.*
import com.yandex.mapkit.transport.TransportFactory
import com.yandex.mapkit.transport.masstransit.*
import com.yandex.mapkit.transport.masstransit.SectionMetadata.SectionData
import com.yandex.mapkit.transport.masstransit.Session
import com.yandex.runtime.Error
import com.yandex.runtime.image.AnimatedImageProvider
import com.yandex.runtime.image.ImageProvider
import com.yandex.runtime.network.NetworkError
import com.yandex.runtime.network.RemoteError
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.kravz.delimap.PermissionHelper.Companion.LOCATION_PERMISSION_REQUEST
import com.kravz.delimap.R
import com.kravz.delimap.databinding.ActivityMainBinding
import ru.tinkoff.decoro.FormattedTextChangeListener
import ru.tinkoff.decoro.MaskImpl
import ru.tinkoff.decoro.slots.PredefinedSlots
import ru.tinkoff.decoro.watchers.FormatWatcher
import ru.tinkoff.decoro.watchers.MaskFormatWatcher
import timber.log.Timber

class MainActivity : Activity(), Session.RouteListener, InputListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mapObjects: MapObjectCollection

    private val mapObjectsDirections: List<PolylineMapObject> = ArrayList()
    private var calcRouter: PedestrianRouter? = null
    private var directionsVisible = true

    private lateinit var suppliers: ArrayList<SupplierOnYaMap>

    private enum class SelectMode {  FROM, TO, NONE  }

    private var calcFirst = true
    private var calcSelect = SelectMode.FROM

    private lateinit var toPointMarker: PlacemarkMapObject
    private lateinit var fromPointMarker: PlacemarkMapObject
    private var fromOnClick: MapObjectTapListener? = null
    private var toOnClick: MapObjectTapListener? = null

    private var lastRouteMetadata: RouteMetadata? = null
    private val calcRoute: MutableList<PolylineMapObject> = ArrayList()
    private val calcRouteRequestListener: Session.RouteListener = object : Session.RouteListener {
        override fun onMasstransitRoutes(routes: List<Route>) {
            if (routes.isNotEmpty()) {
                if (calcRoute.size > 0) {
                    for (obj in calcRoute) {
                        mapObjects.remove(obj)
                    }
                    calcRoute.clear()
                }

                val firstRoute = routes[0]
                lastRouteMetadata = firstRoute.metadata
                for (section in firstRoute.sections) {
                    val polyline =
                        SubpolylineHelper.subpolyline(routes[0].geometry, section.geometry)
                    calcRoute.add(
                        drawSection(
                            section.metadata.data,
                            polyline
                        )
                    )
                }

                val inMKAD = MKAD.inMKAD(fromPointMarker.geometry) && MKAD.inMKAD(toPointMarker.geometry)
                runOnUiThread {
                    binding.formTitle.isVisible = true
                    binding.formTitle.text = getString(
                        R.string.delivery_cost,
                        firstRoute.metadata.weight.walkingDistance.text,
                        if (inMKAD) "100" else "*0")
                }

                val boundingBox = BoundingBox(
                    firstRoute.geometry.points.first(),
                    firstRoute.geometry.points.last()
                )
                var cameraPosition = binding.map.map.cameraPosition(boundingBox)
                cameraPosition = CameraPosition(
                    cameraPosition.target,
                    cameraPosition.zoom - .4f,
                    cameraPosition.azimuth,
                    cameraPosition.tilt
                )
                binding.map.map.move(cameraPosition, Animation(Animation.Type.SMOOTH, 1f), null)
            }
        }

        override fun onMasstransitRoutesError(error: Error) {
            Timber.w(error.javaClass.name)
        }
    }

    private lateinit var searchManager: SearchManager

    private lateinit var currentLocationSearchSession: com.yandex.mapkit.search.Session
    private lateinit var currentLocationSearchListener: com.yandex.mapkit.search.Session.SearchListener

    private lateinit var maskFormatWatcher: MaskFormatWatcher

    private val mailer: Mailer by lazy {
        Mailer(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        MapKitFactory.setApiKey(MAPKIT_API_KEY)
        MapKitFactory.initialize(this)
        TransportFactory.initialize(this)
        DirectionsFactory.initialize(this)
        SearchFactory.initialize(this)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        super.onCreate(savedInstanceState)

        mapObjects = binding.map.map.mapObjects.addCollection()

        searchManager = SearchFactory.getInstance().createSearchManager(SearchManagerType.COMBINED)

        binding.map.map.move(
            CameraPosition(MOSCOW_LOCATION, 10.0f, 0.0f, 0.0f),
            Animation(Animation.Type.SMOOTH, 3F)
        ) {
            GlobalScope.launch {
                suppliers = ArrayList(10)
                for (i in 0..10) {
                    this@MainActivity.runOnUiThread {
                        val routePoints = PointFactory.randomRouteInMKAD()
                        val supplier = SupplierOnYaMap(this@MainActivity, binding.map.map)
                        supplier.startMove(
                            routePoints.first(),
                            routePoints.last()
                        )
                        suppliers.add(supplier)
                    }
                    delay(55)
                }
            }
        }

        binding.apply {
            fromInput.apply {
                setOnEditorActionListener { textView, actionId, keyEvent ->
//                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        searchAndApplyLocationFromInput(
                            textView.text.toString(),
                            binding.toInput.text.toString()
                        )
                        true
//                    } else false
                }
                setOnFocusChangeListener { _, focus ->
                    if (focus)
                        calcSelect = SelectMode.FROM
                }
            }
            toInput.apply {
                setOnEditorActionListener { textView, actionId, keyEvent ->
//                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        searchAndApplyLocationFromInput(
                            binding.fromInput.text.toString(),
                            textView.text.toString()
                        )
                        true
//                    } else false
                }
                setOnFocusChangeListener { _, focus ->
                    if (focus)
                        calcSelect = SelectMode.TO
                }
            }
        }

        binding.map.map.addInputListener(this)

        binding.callButton.setOnClickListener { openCallNummber() }
        binding.showDirections.setOnClickListener {
            showDirections(!directionsVisible.also {
                directionsVisible = it
            })
        }
        binding.findLocation.setOnClickListener {
            findAndApplyLocation()
        }
        binding.calculateDilivery.setOnClickListener {
            val show = !mainPopupVisible
            showMainPopup(show)
            showSendControllers(show)
        }

        calcRouter = TransportFactory.getInstance().createPedestrianRouter()

        val markerIcon = ImageProvider.fromResource(this, R.drawable.ic_baseline_place_24)

        fromPointMarker = mapObjects.addPlacemark(NONE_LOCATION).apply {
            setIcon(markerIcon)
            isVisible = mainPopupVisible
            isDraggable = true
            setDragListener(object : MapObjectDragListener {
                override fun onMapObjectDragStart(p0: MapObject) {
                }

                override fun onMapObjectDrag(p0: MapObject, p1: Point) {
                }

                override fun onMapObjectDragEnd(p0: MapObject) {
                    calcSelect = SelectMode.FROM
                }
            })
            addTapListener(MapObjectTapListener { mapObject, point ->
                calcSelect = SelectMode.FROM
                true
            }.also { fromOnClick = it })
        }

        toPointMarker = mapObjects.addPlacemark(NONE_LOCATION).apply {
            setIcon(markerIcon)
            isVisible = mainPopupVisible
            isDraggable = true
            setDragListener(object : MapObjectDragListener {
                override fun onMapObjectDragStart(p0: MapObject) {
                }

                override fun onMapObjectDrag(p0: MapObject, p1: Point) {
                }

                override fun onMapObjectDragEnd(p0: MapObject) {
                    calcSelect = SelectMode.TO
                }
            })

            addTapListener(MapObjectTapListener { mapObject, point ->
                calcSelect = SelectMode.TO
                true
            }.also { toOnClick = it })
        }

        binding.sendButton.setOnClickListener {
            val phoneNumber = binding.phoneInput.text.toString()
            val from = fromPointMarker.geometry
            val to = toPointMarker.geometry
            binding.sendProgress.isVisible = true
            val lastRouteMetadata = lastRouteMetadata
            mailer.sendNotify(
                arrayOf(
                    "<div><a href=\"tel:${phoneNumber}\">${phoneNumber}</a></div>",
                    if (lastRouteMetadata != null) "<div><a>Дистанция ${lastRouteMetadata.weight.walkingDistance.text}</a></div>" else "",
                    "<div><a href=\"https://yandex.ru/maps?ll=${from.longitude}%2C${from.latitude}&amp;z=16.0\">Яндекс.Карты: От ${fromLocationTitle()}</a></div>",
                    "<div><a href=\"https://yandex.ru/maps?ll=${to.longitude}%2C${to.latitude}&amp;z=16.0\">Яндекс.Карты: До ${binding.toInput.text.toString()}</a></div>"
                ),
                {
                    runOnUiThread {
                        binding.sendButton.setError(null, null)
                        binding.sendProgress.isVisible = false
                    }
                },
                {
                    runOnUiThread {
                        binding.sendButton.error = ""
                        binding.sendProgress.isVisible = false
                        Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        binding.map.map.isRotateGesturesEnabled = false

        maskFormatWatcher = MaskFormatWatcher(
            MaskImpl.createNonTerminated(PredefinedSlots.RUS_PHONE_NUMBER)
        )
        maskFormatWatcher.setCallback(object : FormattedTextChangeListener {
            override fun beforeFormatting(oldValue: String?, newValue: String?): Boolean {
                return false
            }

            override fun onTextFormatted(formatter: FormatWatcher?, newFormattedText: String?) {
                if (!maskFormatWatcher.mask.filled())
                    binding.phoneInput.error = ""
                binding.sendButton.isEnabled = maskFormatWatcher.mask.filled()
            }

        })
        maskFormatWatcher.installOnAndFill(binding.phoneInput)
    }

//    private lateinit var userLocationLayer: UserLocationLayer
//    private var locationObjectListener: UserLocationObjectListener? = null

    fun searchAndApplyLocationFromInput(qFrom: String, qTo: String) {
            searchAndApplyFromLocation(qFrom, false)
            searchAndApplyToLocation(qTo, false)
    }

    private lateinit var findAndApplyLocationListener: LocationListener
    fun findAndApplyLocation() {
        if (PermissionHelper.gpsPermissionGranted(this)) {

            GpsUtils(this).turnGPSOn {}

            binding.locationContainer.visibility = View.VISIBLE
            setCurrentLocationName("Поиск...")

            findAndApplyLocationListener = object : LocationListener {
                override fun onLocationUpdated(location: Location) {
                    applyCurrentLocation(location.position)
                    setCurrentLocationPoint(location.position)
                }

                override fun onLocationStatusUpdated(locationStatus: LocationStatus) {
                    if (locationStatus != LocationStatus.AVAILABLE) {
                        setCurrentLocationName("Не найден")
                        binding.locationContainer.visibility = View.GONE
                    }
                }
            }
            MapKitFactory.getInstance().createLocationManager()
                .requestSingleUpdate(findAndApplyLocationListener)
        } else {
            PermissionHelper.requestGpsPermission(this)
        }
    }

    fun applyCurrentLocation(point: Point) {
        currentLocationSearchListener = object : com.yandex.mapkit.search.Session.SearchListener {
            override fun onSearchResponse(response: Response) {
                val geoObject = response.collection.children.firstOrNull()?.obj
                if (geoObject != null) {
                    setCurrentLocationName(geoObject.name ?: "")
                    setCurrentLocationDescription(geoObject.descriptionText ?: "")
                    val fromLocationTitle = fromLocationTitle()
                    if (fromLocationTitle.isEmpty() || fromLocationTitle == point.toStr())
                        setFromLocation(point, geoObject.name ?: "")
                } else {
                    val pointStr = point.toStr()
                    setFromLocation(point, pointStr)
                    setCurrentLocationName(pointStr) } }

            override fun onSearchError(error: Error) {
                val pointStr = point.toStr()
                val fromLocationTitle = fromLocationTitle()
                if (fromLocationTitle.isEmpty() || fromLocationTitle == pointStr)
                    setFromLocation(point, pointStr)
                setCurrentLocationName(pointStr)
                Timber.wtf("onSearchError>${error.javaClass}") } }
        currentLocationSearchSession = searchManager.submit(
            point, 21, SearchOptions(),
            currentLocationSearchListener
        )
    }

    private val currentLocationPlacemark: PlacemarkMapObject by lazy {
        mapObjects.addPlacemark(
            NONE_LOCATION, AnimatedImageProvider.fromResource(
                this@MainActivity,
                R.drawable.anim_marker
            ), IconStyle()
        ).apply {
            isVisible = true
            useAnimation().play()
            zIndex = 999F
            addTapListener(MapObjectTapListener { mapObject, point ->
                calcSelect = SelectMode.FROM
                true
            }.also { fromOnClick = it })
        }
    }

    fun setCurrentLocationPoint(point: Point) {
        binding.map.map.apply {
            move(
                CameraPosition(point, 15F, 0.0f, 0.0f),
                Animation(Animation.Type.SMOOTH, 3F)
            ) {}
        }
        currentLocationPlacemark.geometry = point
    }

    fun setCurrentLocationName(location: String) {
        binding.locationName.text = location
    }

    fun setCurrentLocationDescription(location: String) {
        binding.locationDescription.text = location
    }

    private lateinit var fromLocationSearchSession: com.yandex.mapkit.search.Session
    private lateinit var fromLocationSearchListener: com.yandex.mapkit.search.Session.SearchListener

    fun fromLocationTitle() = binding.fromInput.text.toString()

    fun searchAndApplyFromLocation(q: Any, replaceInput: Boolean = true) {
        var byPoint = false
        fromLocationSearchListener = object : com.yandex.mapkit.search.Session.SearchListener {
            override fun onSearchResponse(response: Response) {
                val geoObj = response.collection.children.firstOrNull()?.obj
                if (geoObj != null) {
                    val point = geoObj.geometry.firstOrNull()?.point
                    val description = geoObj.descriptionText ?: ""
                    val name = geoObj.name ?: ""
                    Timber.wtf("name=$name")
                    Timber.wtf("description=$description")
                    if (point != null) {
                        setFromLocation(point, if (byPoint) name else description, replaceInput)
                    }
                }
            }

            override fun onSearchError(error: Error) {
                Timber.wtf("onSearchError>${error.javaClass}")
            }
        }
        if (q is String) {
            fromLocationSearchSession = searchManager.submit(
                q,
                VisibleRegionUtils.toPolygon(binding.map.map.visibleRegion),
                DEFAULT_SEARCH_OPTIONS,
                fromLocationSearchListener
            )
        } else if (q is Point) {
            byPoint = true
            fromLocationSearchSession = searchManager.submit(
                q,
                21,
                DEFAULT_SEARCH_OPTIONS,
                fromLocationSearchListener
            )
        }
    }

    fun setFromLocation(point: Point, title: String = "", replaceInput: Boolean = true) {
        if (fromPointMarker.geometry.compareTo(NONE_LOCATION)) {
            fromPointMarker.isVisible = true
        }
        if (title.isNotEmpty()) {
            if (replaceInput) {
                binding.fromInput.setText(title)
            }
        } else {
            searchAndApplyFromLocation(point, replaceInput)
        }
        fromPointMarker.geometry = point
        calcSelect = if (calcFirst) SelectMode.TO else SelectMode.NONE
        rebuildRouteByMarkerFromTo()
    }


    private lateinit var toLocationSearchSession: com.yandex.mapkit.search.Session
    private lateinit var toLocationSearchListener: com.yandex.mapkit.search.Session.SearchListener

    fun searchAndApplyToLocation(q: Any, replaceInput: Boolean = true) {
        var byPoint = false
        toLocationSearchListener = object : com.yandex.mapkit.search.Session.SearchListener {
            override fun onSearchResponse(response: Response) {
                val geoObj = response.collection.children.firstOrNull()?.obj
                if (geoObj != null) {
                    geoObj.aref
                    val point = geoObj.geometry.firstOrNull()?.point
                    val description = geoObj.descriptionText ?: ""
                    val name = geoObj.name ?: ""
                    Timber.wtf("name=$name")
                    Timber.wtf("description=$description")
                    if (point != null) {
                        setToLocation(point, if (byPoint) name else description, replaceInput)
                    }
                }
            }

            override fun onSearchError(error: Error) {
                Timber.wtf("onSearchError>${error.javaClass}")
            }
        }
        if (q is String) {
            toLocationSearchSession = searchManager.submit(
                q,
                VisibleRegionUtils.toPolygon(binding.map.map.visibleRegion),
                DEFAULT_SEARCH_OPTIONS,
                toLocationSearchListener
            )
        } else if (q is Point) {
            byPoint = true
            toLocationSearchSession = searchManager.submit(
                q,
                21,
                DEFAULT_SEARCH_OPTIONS,
                toLocationSearchListener
            )
        }
    }

    fun setToLocation(point: Point, title: String = "", replaceInput: Boolean = true) {
        if (toPointMarker.geometry.compareTo(NONE_LOCATION)) {
            toPointMarker.isVisible = true
        }
        if (title.isNotEmpty()) {
            if (replaceInput) {
                binding.toInput.setText(title)
            }
        } else {
            searchAndApplyToLocation(point)
        }
        toPointMarker.geometry = point
        calcSelect = if (calcFirst) SelectMode.FROM else SelectMode.NONE
        rebuildRouteByMarkerFromTo()
    }


    override fun onStop() {
        binding.map.onStop()
        MapKitFactory.getInstance().onStop()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        MapKitFactory.getInstance().onStart()
        binding.map.onStart()
    }

    override fun onMasstransitRoutes(routes: List<Route>) {
        val points: MutableList<Point> = ArrayList()
        if (routes.isNotEmpty()) {
            for (section in routes[0].sections) {
                val polyline = SubpolylineHelper.subpolyline(
                    routes[0].geometry, section.geometry
                )
                points.addAll(polyline.points)
                drawSection(
                    section.metadata.data,
                    polyline
                )
            }
        }
    }

    override fun onMasstransitRoutesError(error: Error) {
        var errorMessage = "unknown_error_message"
        if (error is RemoteError) {
            errorMessage = "remote_error_message"
        } else if (error is NetworkError) {
            errorMessage = "network_error_message"
        }
        Timber.w(errorMessage)
    }

    private fun drawSection(data: SectionData, geometry: Polyline): PolylineMapObject {
        val polylineMapObject = mapObjects.addPolyline(geometry)
        polylineMapObject.strokeColor = -0x1000000
        polylineMapObject.zIndex = 99f
        return polylineMapObject
    }

    private fun openCallNummber(phoneNumber: String = "0123456789") {
        val intent = Intent(Intent.ACTION_DIAL)
        intent.data = Uri.parse("tel:$phoneNumber")
        startActivity(intent)
    }

    private fun showDirections(show: Boolean) {
        for (`object` in mapObjectsDirections) {
            `object`.isVisible = show
        }
        suppliers.forEach {
            it.hide(!show)
        }
    }

    val mainPopupVisible
    get() = binding.deliverPopup.visibility == View.VISIBLE

    private fun showMainPopup(show: Boolean) {
        binding.deliverPopup.visibility = if (show) View.VISIBLE else View.GONE
    }

    val sendControllersVisible
    get() = binding.sendButton.visibility == View.VISIBLE

    private fun showSendControllers(show: Boolean) {
        binding.apply {
            phoneInputContainer.visibility = if (show) View.VISIBLE else View.GONE
            sendButton.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    override fun onMapTap(map: Map, point: Point) {
        if (mainPopupVisible) {
            when (calcSelect) {
                SelectMode.FROM -> {
                    setFromLocation(point)
                    if (calcFirst) return
                }
                SelectMode.TO -> {
                    setToLocation(point)
                    if (calcFirst) calcFirst = false
                }
                else -> return
            }

            rebuildRouteByMarkerFromTo()
        }
    }

    fun rebuildRouteByMarkerFromTo() {
        if (fromPointMarker.geometry.latitude != NONE_LOCATION.latitude
            && fromPointMarker.geometry.longitude != NONE_LOCATION.longitude
            && toPointMarker.geometry.latitude != NONE_LOCATION.latitude
            && toPointMarker.geometry.longitude != NONE_LOCATION.longitude) {
            val points: MutableList<RequestPoint> = arrayListOf(
                RequestPoint(fromPointMarker.geometry, RequestPointType.WAYPOINT, null),
                RequestPoint(toPointMarker.geometry, RequestPointType.WAYPOINT, null)
            )
            calcRouter!!.requestRoutes(points, TimeOptions(), calcRouteRequestListener)
        }
    }

    override fun onMapLongTap(map: Map, point: Point) {
    }

//    fun drawMKAD() {
//        val arrayPoints = ArrayList<Point>(200)
//        Timber.wtf("Start reading")
//        with(JsonReader(InputStreamReader(resources.openRawResource(R.raw.moscow), "UTF-8"))) {
//            beginObject()
//            while (hasNext()) {
//                if (nextName() == "coordinates") {
//                    beginArray()
//                    beginArray()
//                    while (hasNext()) {
//                        beginArray()
//                        arrayPoints.add(Point(nextDouble(), nextDouble()))
//                        Timber.wtf("next point")
//                        endArray()
//                    }
//                    break
//                }
//                skipValue()
//            }
//        }
//        Timber.wtf("Finish reading")
//
//        var f = arrayPoints.first()
//
//        var minX = f.latitude
//        var minY = f.longitude
//        var maxX = f.latitude
//        var maxY = f.longitude
//
//        arrayPoints.forEach {
//            val x = it.latitude
//            val y = it.longitude
//
//            if (x > maxX) maxX = x
//            else if (x < minX) minX = x
//            if (y > maxY) maxY = y
//            else if (y < minY) minY = y
//        }
//
//        Timber.wtf("maxX=${maxX}")
//        Timber.wtf("minX=${minX}")
//        Timber.wtf("maxY=${maxY}")
//        Timber.wtf("minY=${minY}")
//
//        val polygon = Polygon(LinearRing(arrayPoints), arrayListOf())
//        mapObjects.addPolygon(polygon).apply {
//            fillColor = Color.BLUE
//        }
//    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            findAndApplyLocation()
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    companion object {
        const val MAPKIT_API_KEY = "810e58a1-cca3-4d98-8eba-44c6678ca8cd"
        val MOSCOW_LOCATION = Point(55.753215, 37.622504)
        val NONE_LOCATION = MOSCOW_LOCATION

        val DEFAULT_SEARCH_OPTIONS = SearchOptions().apply {
            searchTypes = SearchType.GEO.value
        }
    }
}