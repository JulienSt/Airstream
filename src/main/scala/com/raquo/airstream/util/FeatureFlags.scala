package com.raquo.airstream.util

import com.raquo.airstream.core.Runtime

/** #NOTE CONTRIBUTORS: DO NOT OVER-USE FEATURE FLAGS!
  *
  * They should only be used for temporary migration and diagnostic helpers.
  *
  * For example, a boolean flag here could let users disable
  * new changes to Airstream behaviour, helping users verify
  * whether that change is causing the problems they might be
  * observing during a migration to a new version of Airstream.
  *
  * Third party libraries and any other non-end-user code:
  *  - must never change these values
  *  - must never require that users change these values from defaults
  *  - should NOT modify their own logic based on these flags,
  *    as that is likely to be counter-productive to the user's
  *    debugging efforts.
  *
  * Each flag is a setting of the default [[Runtime]]: reading or writing it here reads or writes the default runtime's
  * copy. A runtime created with `Runtime.confined()` takes the values in force at its creation and keeps its own.
  */
object FeatureFlags {

  /** If true, EventBus will check .isStarted condition inside
    * the new transaction, not before creating said transaction.
    *
    * This fixes https://github.com/raquo/Airstream/issues/155
    *
    * #Warning: Should be enabled (true).
    *  - Do not disable except temporarily, to aid migration to V18.
    *  - Will be removed in a future version.
    */
  @deprecated("V18_EVENTBUS_ISSTARTED_FIX_155 disabled: reverted to pre-v18 behaviour. See https://github.com/raquo/Airstream/issues/155", since = "18.0.0-M6")
  def V18_EVENTBUS_ISSTARTED_FIX_155: Boolean = Runtime.default.eventBusIsStartedFix155

  @deprecated("V18_EVENTBUS_ISSTARTED_FIX_155 disabled: reverted to pre-v18 behaviour. See https://github.com/raquo/Airstream/issues/155", since = "18.0.0-M6")
  def V18_EVENTBUS_ISSTARTED_FIX_155_=(value: Boolean): Unit = {
    Runtime.default.eventBusIsStartedFix155 = value
  }

  @deprecated("V18_TRX_ONSTART_ADD_FAIL_EARLY disabled: reverted to pre-v18 behaviour.", since = "18.0.0-M6")
  def V18_TRX_ONSTART_ADD_FAIL_EARLY: Boolean = Runtime.default.trxOnStartAddFailEarly

  @deprecated("V18_TRX_ONSTART_ADD_FAIL_EARLY disabled: reverted to pre-v18 behaviour.", since = "18.0.0-M6")
  def V18_TRX_ONSTART_ADD_FAIL_EARLY_=(value: Boolean): Unit = {
    Runtime.default.trxOnStartAddFailEarly = value
  }

  @deprecated("V18_TRX_ONSTART_FIX_144 disabled: reverted to pre-v18 behaviour. See https://github.com/raquo/Airstream/issues/144", since = "18.0.0-M6")
  def V18_TRX_ONSTART_FIX_144: Boolean = Runtime.default.trxOnStartFix144

  @deprecated("V18_TRX_ONSTART_FIX_144 disabled: reverted to pre-v18 behaviour. See https://github.com/raquo/Airstream/issues/144", since = "18.0.0-M6")
  def V18_TRX_ONSTART_FIX_144_=(value: Boolean): Unit = {
    Runtime.default.trxOnStartFix144 = value
  }

  @deprecated("V18_IMMEDIATE_DYNSUB_REMOVAL_FIX_145 disabled: reverted to pre-v18 behaviour. See https://github.com/raquo/Airstream/issues/145", since = "18.0.0-M6")
  def V18_IMMEDIATE_DYNSUB_REMOVAL_FIX_145: Boolean = Runtime.default.immediateDynSubRemovalFix145

  @deprecated("V18_IMMEDIATE_DYNSUB_REMOVAL_FIX_145 disabled: reverted to pre-v18 behaviour. See https://github.com/raquo/Airstream/issues/145", since = "18.0.0-M6")
  def V18_IMMEDIATE_DYNSUB_REMOVAL_FIX_145_=(value: Boolean): Unit = {
    Runtime.default.immediateDynSubRemovalFix145 = value
  }
}
