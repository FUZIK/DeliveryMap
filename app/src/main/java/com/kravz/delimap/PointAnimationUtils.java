package com.kravz.delimap;

import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;

import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.map.PlacemarkMapObject;

// https://fooobar.com/questions/217529/how-to-animate-marker-in-android-map-api-v2

public class PointAnimationUtils {
    public static void animateMarker(Point destination, PlacemarkMapObject marker, long duration) {
        if (marker != null) {
            Point startPosition = marker.getGeometry();
            Point endPosition = new Point(destination.getLatitude(), destination.getLongitude());

            PointInterpolator pointInterpolator = new PointInterpolator.LinearFixed();
            ValueAnimator valueAnimator = ValueAnimator.ofFloat(0, 1);
            valueAnimator.setDuration(duration);
            valueAnimator.setInterpolator(new LinearInterpolator());
            valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator animation) {
                    try {
                        float v = animation.getAnimatedFraction();
                        Point newPosition = pointInterpolator.interpolate(v, startPosition, endPosition);
                        marker.setGeometry(newPosition);
                    } catch (Exception ex) {
                        // I don't care atm..
                    }
                }
            });

            valueAnimator.start();
        }
    }
}
