package com.kravz.delimap

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionHelper {
    companion object {
        fun gpsPermissionGranted(context: Context) =
            ContextCompat.checkSelfPermission(context, LOCATION_PERMISSION_NAME) == PackageManager.PERMISSION_GRANTED

        fun requestGpsPermission(activity: Activity) {
            ActivityCompat.requestPermissions(activity,
                arrayOf(LOCATION_PERMISSION_NAME), LOCATION_PERMISSION_REQUEST
            )
        }

        const val LOCATION_PERMISSION_NAME = "android.permission.ACCESS_FINE_LOCATION"
        const val LOCATION_PERMISSION_REQUEST = 8989
    }
}