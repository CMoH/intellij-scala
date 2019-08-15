package org.jetbrains.plugins.scala.caches.stats

case class TracerData(id: String,
                      name: String,
                      fromCacheCount: Int,
                      actualCount: Int,
                      maxTime: Int,
                      totalTime: Int) {

  def totalCount: Int = fromCacheCount + actualCount

  def avgTime: Double = {
    if (actualCount == 0) 0
    else {
      val exact = totalTime.toDouble / actualCount
      (exact * 100).round / 100
    }
  }
}
