// Copyright (c) 2013-2018 Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package doobie.tagless

import cats._
import cats.effect.{ Resource, Sync }
import cats.implicits._
import doobie.{ Fragment, Write }
import doobie.tagless.async._
import fs2.{ Pipe, Sink, Stream }

final case class PreparedStatement[F[_]: Sync](jdbc: AsyncPreparedStatement[F], interp: Interpreter[F]) {

  // janky!
  def setArguments(f: Fragment): F[Unit] =
    interp.rts.newBlockingPrimitive(f.unsafePrepare(jdbc.value))

  /** Execute this statement as a query, yielding a ResultSet[F] that will be cleaned up. */
  def executeQuery(implicit ev: Functor[F]): Resource[F, ResultSet[F]] =
    Resource.make(jdbc.executeQuery.map(interp.forResultSet))(_.jdbc.close)

  /**
   * Set the statement parameters using `A` flattened to a column vector, starting at the given
   * offset.
   */
  def set[A: Write](a: A, offset: Int): F[Unit] =
    interp.rts.newBlockingPrimitive(Write[A].unsafeSet(jdbc.value, offset, a))

  /**
   * Construct a sink for batch update, discarding update counts. Note that the result array will
   * be computed by JDBC and then discarded, so this call has the same cost as `rawPipe`.
   */
  def sink[A: Write]: Sink[F, A] = as =>
    as.through(rawPipe[A]).drain

  /**
   * Construct a pipe for batch update, translating each input value into its update count. Unless
   * you're inspecting the results it's cheaper to use `sink`.
   */
  def pipe[A: Write]: Pipe[F, A, BatchResult] = as =>
    as.through(rawPipe[A]).flatMap(a => Stream.emits(a)).map(BatchResult.fromJdbc)

  /**
   * Construct a pipe for batch update, emitting a single array containing raw JDBC update results.
   * In the case where the result is discarded (i.e., with `pipe`) there is no need to interpret
   * the results, hence "raw". This is private to the implementation.
   */
  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  private def rawPipe[A](
    implicit wa: Write[A]
  ): Pipe[F, A, Array[Int]] = as =>
    as.chunks.evalMap { chunk =>
      interp.rts.newBlockingPrimitive {
        interp.rts.log.unsafe.trace(jdbc.value, {
          val types = wa.puts.map { case (g, _) =>
            g.typeStack.head.fold("«unknown»")(_.toString)
          }
          s"addBatch(${chunk.size}) of ${types.mkString(", ")}"
        })
        chunk.foreach { a =>
          wa.unsafeSet(jdbc.value, 1, a)
          jdbc.value.addBatch
        }
      }
    } .drain ++ Stream.eval(jdbc.executeBatch)

}