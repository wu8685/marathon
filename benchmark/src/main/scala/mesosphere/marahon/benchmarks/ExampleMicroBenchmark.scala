package mesosphere.marahon.benchmarks

import org.openjdk.jmh.annotations.Benchmark

class ExampleMicroBenchmark {
  @Benchmark
  def example(): Unit = {
    0.until(10).sum
  }
}
