package com.kravz.delimap

import com.yandex.mapkit.geometry.Point

fun Point.toStr() = "${PointFactory.roundForCords(latitude)}, ${PointFactory.roundForCords(longitude)}"

fun Point.compareTo(p: Point) =
    this.latitude == p.latitude
    && this.longitude == p.longitude