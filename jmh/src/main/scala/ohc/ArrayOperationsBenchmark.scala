package ohc

import ohc.support.Nat._
import org.openjdk.jmh.annotations._
import ohc.annotation.struct
import shapeless.syntax.singleton._

@State(Scope.Thread)
class ArrayOperationsBenchmark {
  import AllocationBenchmark._
  val stack = new Stack(new DirectMemory(Point.size * 100000))

  val index = int2Nat(5)

  @Benchmark
  @BenchmarkMode(scala.Array(Mode.SampleTime))
  def measureForeach() = {
    stack.contextualized { implicit c =>
      val arr = Array(10000, Point)
      arr.foreach { point =>
        point.x = 5
        point.y = 8
      }
      arr(index).y
    }
  }

  @Benchmark
  @BenchmarkMode(scala.Array(Mode.SampleTime))
  def measureFold() = {
    stack.contextualized { implicit c =>
      val arr = Array(10000, Point)
      arr.fold(0.0) { (accum, point) =>
        point.x = 5
        point.y = 8
        accum + point.y
      }
    }
  }
}
