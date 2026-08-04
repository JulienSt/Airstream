package com.raquo.airstream.platform

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/** Pins down when a scheduled callback actually runs on Scala Native.
  *
  * This is the half of the timer contract that cannot live in the shared spec, because it genuinely differs. On JavaScript
  * the event loop delivers a callback by itself; a Scala Native program has no loop at all until something drives one.
  * Rather than invent a thread and its locking, the callbacks wait here until a caller says "run whatever is due" — which
  * in a terminal application is the same loop that is already blocking on the keyboard.
  *
  * That makes time an argument instead of a hidden dependency, so every case below is exercised without a single sleep.
  */
class NativeTimersSpec extends AnyFunSpec with Matchers {

    /** Start each case from an empty queue, since the scheduler is process-wide by design. */
    private def freshQueue(): Unit = Timers.clearAll()

    describe("a callback that is not yet due") {

        it("does not run") {
            freshQueue()
            var ran = false
            Timers.setTimeout(100)({ ran = true })
            Timers.runDue(50)
            ran shouldBe false
        }

        it("is still counted as pending") {
            freshQueue()
            Timers.setTimeout(100)(())
            Timers.runDue(50) shouldBe 0
            Timers.pendingCount shouldBe 1
        }
    }

    describe("a callback whose time has come") {

        it("runs when the clock reaches its deadline") {
            freshQueue()
            var ran = false
            Timers.setTimeout(100)({ ran = true })
            Timers.runDue(100)
            ran shouldBe true
        }

        it("runs when the clock has passed its deadline, rather than being skipped") {
            freshQueue()
            var ran = false
            Timers.setTimeout(100)({ ran = true })
            Timers.runDue(5000)
            ran shouldBe true
        }

        it("runs exactly once, however often the clock is pumped afterwards") {
            freshQueue()
            var runs = 0
            Timers.setTimeout(10)({ runs += 1 })
            Timers.runDue(20)
            Timers.runDue(30)
            Timers.runDue(40)
            runs shouldBe 1
        }
    }

    describe("several callbacks") {

        it("run in deadline order, not in the order they were scheduled") {
            freshQueue()
            val order = List.newBuilder[String]
            Timers.setTimeout(200)({ order += "late" })
            Timers.setTimeout(50)({ order += "early" })
            Timers.runDue(300)
            order.result() shouldBe List("early", "late")
        }

        it("only run the ones that are actually due") {
            freshQueue()
            val order = List.newBuilder[String]
            Timers.setTimeout(50)({ order += "due" })
            Timers.setTimeout(500)({ order += "not yet" })
            Timers.runDue(100)
            order.result() shouldBe List("due")
        }
    }

    describe("cancelling") {

        it("stops a callback from ever running") {
            freshQueue()
            var ran = false
            val handle = Timers.setTimeout(10)({ ran = true })
            Timers.clearTimeout(handle)
            Timers.runDue(1000)
            ran shouldBe false
        }

        it("leaves the other callbacks alone") {
            freshQueue()
            var survived = false
            val cancelled = Timers.setTimeout(10)(())
            Timers.setTimeout(10)({ survived = true })
            Timers.clearTimeout(cancelled)
            Timers.runDue(1000)
            survived shouldBe true
        }
    }

    describe("a callback scheduled from inside another") {

        it("does not run in the same pump, so one pump cannot loop forever") {
            freshQueue()
            var inner = false
            Timers.setTimeout(10)(Timers.setTimeout(0)({ inner = true }))
            Timers.runDue(20)
            inner shouldBe false
        }

        it("runs on the next pump") {
            freshQueue()
            var inner = false
            Timers.setTimeout(10)(Timers.setTimeout(0)({ inner = true }))
            Timers.runDue(20)
            Timers.runDue(21)
            inner shouldBe true
        }
    }

    describe("the pump") {

        it("reports how many callbacks it ran, so a driver can tell whether it made progress") {
            freshQueue()
            Timers.setTimeout(10)(())
            Timers.setTimeout(20)(())
            Timers.runDue(100) shouldBe 2
        }

        it("reports zero on an empty queue rather than failing") {
            freshQueue()
            Timers.runDue(100) shouldBe 0
        }
    }
}
