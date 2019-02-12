package org.jetbrains.plugins.scala
package lang
package refactoring

/**
  * Pavel Fatin
  */
case class Associations(override val associations: Array[Association])
  extends AssociationsData(associations, Associations)
    with Cloneable {

  override def clone(): Associations = copy()
}

object Associations extends AssociationsData.Companion(classOf[Associations], "ScalaReferenceData")