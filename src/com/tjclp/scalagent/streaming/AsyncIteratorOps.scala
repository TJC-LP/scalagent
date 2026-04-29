package com.tjclp.scalagent.streaming

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import zio.*
import zio.stream.*

/** JavaScript AsyncIterator result interface */
@js.native
trait IteratorResult[+T] extends js.Object:
  val done: js.UndefOr[Boolean] = js.native
  val value: js.UndefOr[T]      = js.native

/** JavaScript AsyncIterator interface */
@js.native
trait AsyncIterator[+T] extends js.Object:
  def next(): js.Promise[IteratorResult[T]] = js.native

/** JavaScript AsyncGenerator interface (extends AsyncIterator with control methods) */
@js.native
trait AsyncGenerator[+T, TReturn, TNext] extends AsyncIterator[T]:
  def `return`(value: js.UndefOr[TReturn]): js.Promise[IteratorResult[T]] = js.native
  def `throw`(e: js.Any): js.Promise[IteratorResult[T]]                   = js.native

/** Operations for converting JavaScript AsyncIterators to ZIO Streams */
object AsyncIteratorOps:

  /**
   * Convert a JavaScript AsyncIterator to a ZStream.
   *
   * This is the core bridge between JavaScript's async iteration protocol and ZIO's streaming model. It uses
   * `ZStream.unfoldZIO` to lazily pull values from the iterator, converting each `js.Promise` to a `ZIO` effect.
   *
   * @tparam A
   *   The element type
   * @param iterator
   *   The JavaScript AsyncIterator to convert
   * @return
   *   A ZStream that emits elements from the iterator
   */
  def toZStream[A](iterator: AsyncIterator[A]): ZStream[Any, Throwable, A] =
    ZStream.unfoldZIO(iterator) { iter =>
      ZIO
        .fromPromiseJS(iter.next())
        .map { result =>
          val isDone = result.done.getOrElse(false)
          if isDone then None
          else result.value.toOption.map(v => (v, iter))
        }
    }

  /**
   * Convert with proper cleanup on stream termination.
   *
   * This variant ensures the generator's cleanup logic runs when the stream is interrupted or completes.
   *
   * @tparam A
   *   The element type
   * @tparam R
   *   The return type of the generator
   * @param generator
   *   The JavaScript AsyncGenerator to convert
   * @param cleanup
   *   Cleanup action to run on termination
   * @return
   *   A ZStream with bracketed resource management
   */
  def toZStreamWithCleanup[A, R](
    generator: AsyncGenerator[A, R, Unit],
    cleanup: => Task[Unit],
  ): ZStream[Any, Throwable, A] =
    ZStream
      .acquireReleaseWith(ZIO.succeed(generator))(_ => cleanup.ignore)
      .flatMap(gen => toZStream(gen))

  /**
   * Convert with automatic return() call on termination.
   *
   * This calls the generator's `return()` method when the stream ends, which is the proper way to clean up
   * JavaScript generators.
   *
   * @tparam A
   *   The element type
   * @param generator
   *   The JavaScript AsyncGenerator to convert
   * @return
   *   A ZStream that properly terminates the generator
   */
  def toZStreamWithReturn[A](
    generator: AsyncGenerator[A, Unit, Unit]
  ): ZStream[Any, Throwable, A] =
    toZStreamWithCleanup(
      generator,
      ZIO.fromPromiseJS(generator.`return`(js.undefined)).unit,
    )
end AsyncIteratorOps
