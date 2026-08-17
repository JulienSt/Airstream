package com.raquo.airstream.fixtures

import com.raquo.airstream.ownership.{Owner, Subscription}

class TestableOwner extends Owner {

  def _testSubscriptions: List[Subscription] = {
    val result = List.newBuilder[Subscription]
    subscriptions.forEach(subscription => result += subscription)
    result.result()
  }

  override def killSubscriptions(): Unit = {
    super.killSubscriptions()
  }
}
