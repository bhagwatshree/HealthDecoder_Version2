package com.healthdecoder.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.R
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.DemoDataSeeder
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val useLogo: Boolean = false,
    val icon: ImageVector? = null,
    val headline: String,
    val body: String
)

// First 3 informational pages; a 4th ("Try Demo" / "Skip") page is added by OnboardingScreen itself.
private val ONBOARDING_INFO_PAGES = listOf(
    OnboardingPage(
        useLogo = true,
        headline = "Scan any medical report",
        body = "Turn paper lab reports, prescriptions and discharge summaries into organized, " +
            "searchable digital records — right from your phone's camera."
    ),
    OnboardingPage(
        icon = Icons.Default.Insights,
        headline = "Understand it instantly",
        body = "AI reads every test value, medicine and date on the page, so you see what a " +
            "report actually means without hunting through medical jargon."
    ),
    OnboardingPage(
        icon = Icons.Default.Medication,
        headline = "Never miss a dose or a follow-up",
        body = "Medicine reminders fire at the right time every day — even for a twice-a-week " +
            "medicine — and doctor appointments get their own reminder too."
    )
)

/**
 * First-launch onboarding carousel, shown once right after the medical disclaimer is accepted
 * (see MainNavigation's showOnboarding gating) — never again afterwards. Last page offers
 * "Try Demo" (seeds the "Aisha (Demo)" sample patient via DemoDataSeeder) or "Skip".
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lastPageIndex = ONBOARDING_INFO_PAGES.size // index of the "Try Demo"/"Skip" page
    val pagerState = rememberPagerState(pageCount = { lastPageIndex + 1 })
    var isSeedingDemo by remember { mutableStateOf(false) }

    fun finish() {
        AppSettings.setOnboardingSeen(context, true)
        onFinish()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (page < lastPageIndex) {
                OnboardingInfoPage(ONBOARDING_INFO_PAGES[page])
            } else {
                OnboardingFinalPage(
                    isSeedingDemo = isSeedingDemo,
                    onTryDemo = {
                        isSeedingDemo = true
                        coroutineScope.launch {
                            runCatching { DemoDataSeeder.seedDemoData(context) }
                            isSeedingDemo = false
                            finish()
                        }
                    },
                    onSkip = { finish() }
                )
            }
        }

        // Top-corner "Skip" link on pages 1-3 — jumps straight to the last page's choice instead
        // of leaving the user to swipe through everything to reach it.
        if (pagerState.currentPage < lastPageIndex) {
            TextButton(
                onClick = {
                    coroutineScope.launch { pagerState.animateScrollToPage(lastPageIndex) }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
            ) {
                Text(tr("Skip"), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Page indicator dots
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(lastPageIndex + 1) { index ->
                val selected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .size(if (selected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                )
            }
        }
    }
}

@Composable
private fun OnboardingInfoPage(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (page.useLogo) {
                Image(
                    painter = painterResource(id = R.drawable.ic_health_decoder_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            } else if (page.icon != null) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(40.dp))
        Text(
            tr(page.headline),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(16.dp))
        Text(
            tr(page.body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(80.dp)) // leaves room for the indicator dots at the bottom
    }
}

@Composable
private fun OnboardingFinalPage(
    isSeedingDemo: Boolean,
    onTryDemo: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Celebration,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(Modifier.height(40.dp))
        Text(
            tr("See it in action"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "${tr("Add a sample patient —")} \"${DemoDataSeeder.DEMO_PATIENT_NAME}\" — " +
                tr("with example reports, reminders and an appointment already filled in, so you can explore every screen " +
                    "before you scan anything real. Remove it any time from Account."),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onTryDemo,
            enabled = !isSeedingDemo,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isSeedingDemo) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(tr("Try Demo"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onSkip,
            enabled = !isSeedingDemo,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tr("Skip"))
        }
        Spacer(Modifier.height(80.dp)) // leaves room for the indicator dots at the bottom
    }
}
