package com.tjclp.scalagent.core

import scala.annotation.implicitNotFound

/** Visibility / classification markers for reviewable outputs.
  *
  * These markers model who is allowed to see a particular output. They are
  * intentionally type-level so review segregation can become a compile-time
  * property rather than a convention.
  */
sealed trait Visibility
sealed trait Public extends Visibility
sealed trait Internal extends Visibility
sealed trait Secret extends Visibility
sealed trait TopSecret extends Visibility

/** Wrap a value with a visibility label. */
final case class Classified[+A, L <: Visibility](value: A)

/** Type-level evidence that an actor cleared for `Viewer` may see data labeled `Data`.
  *
  * Current lattice:
  *   Public <= Internal <= Secret <= TopSecret
  */
@implicitNotFound("Clearance ${Viewer} is not permitted to review visibility level ${Data}")
trait CanSee[Viewer <: Visibility, Data <: Visibility]

object CanSee:
  given publicToPublic: CanSee[Public, Public] with {}

  given internalToPublic: CanSee[Internal, Public] with {}
  given internalToInternal: CanSee[Internal, Internal] with {}

  given secretToPublic: CanSee[Secret, Public] with {}
  given secretToInternal: CanSee[Secret, Internal] with {}
  given secretToSecret: CanSee[Secret, Secret] with {}

  given topSecretToPublic: CanSee[TopSecret, Public] with {}
  given topSecretToInternal: CanSee[TopSecret, Internal] with {}
  given topSecretToSecret: CanSee[TopSecret, Secret] with {}
  given topSecretToTopSecret: CanSee[TopSecret, TopSecret] with {}
