package com.raquo.airstream.ownership

import com.raquo.airstream.UnitSpec

import scala.collection.mutable

/** Covers a widget-like parent owner whose activation owns a nested child owner. */
class NestedDynamicOwnerSpec extends UnitSpec {

  final private class NestedOwners(
    val parent: DynamicOwner,
    val child: DynamicOwner,
    val parentLink: DynamicSubscription,
    val childLink: DynamicSubscription,
    val effects: mutable.Buffer[String]
  )

  private def nestedOwners(): NestedOwners = {
    val effects = mutable.Buffer[String]()
    val parent = new DynamicOwner(() => fail("parent owner used after kill"))
    val child = new DynamicOwner(() => fail("child owner used after kill"))
    val childLink = DynamicSubscription.unsafe(child, owner => {
      effects += "child-on"
      new Subscription(owner, () => effects += "child-off")
    })
    val parentLink = DynamicSubscription.unsafe(parent, owner => {
      effects += "parent-on"
      child.activate()
      new Subscription(owner, () => {
        child.deactivate()
        effects += "parent-off"
      })
    })
    new NestedOwners(parent, child, parentLink, childLink, effects)
  }

  it("activates and deactivates a nested owner with its parent") {
    val nested = nestedOwners()

    nested.parent.activate()
    nested.parent.isActive shouldBe true
    nested.child.isActive shouldBe true
    nested.effects shouldBe mutable.Buffer("parent-on", "child-on")

    nested.parent.deactivate()
    nested.parent.isActive shouldBe false
    nested.child.isActive shouldBe false
    nested.effects shouldBe mutable.Buffer("parent-on", "child-on", "child-off", "parent-off")

    nested.parentLink.kill()
    nested.childLink.kill()
    nested.parent.hasSubscriptions shouldBe false
    nested.child.hasSubscriptions shouldBe false
  }

  it("reactivates the nested branch once per cycle without retaining active subscriptions") {
    val nested = nestedOwners()

    nested.parent.activate()
    nested.parent.deactivate()
    nested.parent.activate()
    nested.parent.deactivate()

    nested.effects shouldBe mutable.Buffer(
      "parent-on",
      "child-on",
      "child-off",
      "parent-off",
      "parent-on",
      "child-on",
      "child-off",
      "parent-off"
    )
    nested.parent.isActive shouldBe false
    nested.child.isActive shouldBe false
    nested.parent.numSubscriptions shouldBe 1
    nested.child.numSubscriptions shouldBe 1

    nested.parentLink.kill()
    nested.childLink.kill()
  }
}
