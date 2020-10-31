package com.kravz.delimap;

import com.yandex.mapkit.geometry.Point;

interface PointInterpolator {
    Point interpolate(float fraction, Point a, Point b);

    class LinearFixed implements PointInterpolator {
        @Override
        public Point interpolate(float fraction, Point a, Point b) {
            double lat = (b.getLatitude() - a.getLatitude()) * fraction + a.getLatitude();
            double lngDelta = b.getLongitude() - a.getLongitude();
            // Take the shortest path across the 180th meridian.
            if (Math.abs(lngDelta) > 180) {
                lngDelta -= Math.signum(lngDelta) * 360;
            }
            double lng = lngDelta * fraction + a.getLongitude();
            return new Point(lat, lng);
        }
    }
}