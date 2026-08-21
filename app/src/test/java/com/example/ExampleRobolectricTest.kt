package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.viewmodel.FocusViewModel
import com.example.ui.viewmodel.InsightsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Dedication", appName)
  }

  @Test
  fun `verify application and view models initialize without crash`() {
    val app = ApplicationProvider.getApplicationContext<FocusLockApp>()
    assertNotNull(app)
    assertNotNull(app.database)
    assertNotNull(app.focusRepository)
    assertNotNull(app.preferencesRepository)
    assertNotNull(app.usageStatsRepository)
    assertNotNull(app.authRepository)

    val focusViewModel = FocusViewModel(app)
    assertNotNull(focusViewModel)

    val insightsViewModel = InsightsViewModel(app)
    assertNotNull(insightsViewModel)
  }
}

