package com.raquo.airstream.platform

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/** Pins down the delayed-callback bookkeeping Airstream's timing operators are built on, for every platform it runs on.
  *
  * Only two operations are needed: schedule something for later, and cancel it. Airstream never asks for a repeating
  * timer — the periodic stream reschedules itself — which is worth knowing before building an interval nobody calls.
  *
  * What this spec covers is deliberately the part that means the same thing on both platforms: handing out handles, and
  * cancelling them safely. Firing does not mean the same thing: on JavaScript the event loop delivers the callback on its
  * own, while a Scala Native program has no loop unless something drives one. That difference is real rather than
  * incidental, so it is tested where it lives — in the platform's own spec — instead of being papered over here.
  */
class TimersSpec extends AnyFunSpec with Matchers {

    describe("scheduling") {

        it("hands back a handle that cancelling accepts") {
            val handle = Timers.setTimeout(1000)(())
            noException should be thrownBy Timers.clearTimeout(handle)
        }

        it("hands back a distinct handle for each scheduled callback") {
            val first  = Timers.setTimeout(1000)(())
            val second = Timers.setTimeout(1000)(())
            first should not be second
            Timers.clearTimeout(first)
            Timers.clearTimeout(second)
        }
    }

    describe("cancelling") {

        it("accepts a handle that is still pending") {
            val handle = Timers.setTimeout(10000)(())
            noException should be thrownBy Timers.clearTimeout(handle)
        }

        it("accepts the same handle twice, because a caller cannot always know whether it already fired") {
            val handle = Timers.setTimeout(10000)(())
            Timers.clearTimeout(handle)
            noException should be thrownBy Timers.clearTimeout(handle)
        }

        it("cancels only the handle it was given") {
            val kept      = Timers.setTimeout(10000)(())
            val cancelled = Timers.setTimeout(10000)(())
            Timers.clearTimeout(cancelled)
            noException should be thrownBy Timers.clearTimeout(kept)
        }
    }

    describe("a zero delay") {

        it("is accepted rather than rejected, because that is how a callback is deferred to the next turn") {
            val handle = Timers.setTimeout(0)(())
            noException should be thrownBy Timers.clearTimeout(handle)
        }

        it("still hands back a handle distinct from another zero-delay one") {
            val first  = Timers.setTimeout(0)(())
            val second = Timers.setTimeout(0)(())
            first should not be second
            Timers.clearTimeout(first)
            Timers.clearTimeout(second)
        }
    }
}
