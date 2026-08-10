package com.deeplinkly.flutter_deeplinkly

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/*
 * Unit tests for the Kotlin portion of this plugin's implementation.
 *
 * Run from the command line with `./gradlew testDebugUnitTest` in the
 * `example/android/` directory, or from an IDE with JUnit support.
 */
@RunWith(RobolectricTestRunner::class)
internal class FlutterDeeplinklyPluginTest {

  /**
   * A plugin that never went through onAttachedToEngine has no API key, so every
   * call except getDeeplinklyId short-circuits to the disabled envelope rather
   * than doing any work. (The previous version of this test asserted
   * `getPlatformVersion` returned "Android <release>", which has not been the
   * behaviour since the SDK-disabled guard was added.)
   */
  @Test
  fun onMethodCall_withoutApiKey_reportsSdkDisabled() {
    val plugin = FlutterDeeplinklyPlugin()

    val call = MethodCall("getPlatformVersion", null)
    val mockResult: MethodChannel.Result = Mockito.mock(MethodChannel.Result::class.java)
    plugin.onMethodCall(call, mockResult)

    val captor = ArgumentCaptor.forClass(Any::class.java)
    Mockito.verify(mockResult).success(captor.capture())

    @Suppress("UNCHECKED_CAST")
    val payload = captor.value as Map<String, Any?>
    assertEquals(false, payload["success"])
    assertEquals("SDK_DISABLED", payload["error_code"])
  }
}
