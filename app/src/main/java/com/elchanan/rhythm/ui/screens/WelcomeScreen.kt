package com.elchanan.rhythm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.elchanan.rhythm.ui.theme.Accent
import com.elchanan.rhythm.ui.theme.Accent2
import com.elchanan.rhythm.ui.theme.Bg
import com.elchanan.rhythm.ui.theme.RhythmMark
import com.elchanan.rhythm.ui.theme.TextSecondary
import com.elchanan.rhythm.ui.components.rememberMetrics

/**
 * Shown once, before the library has been analysed.
 *
 * The first few minutes are the worst the app ever looks: the shelves are
 * built from listening history that does not exist yet, and the analyser is
 * still working through the files. Without a word of explanation that reads as
 * a broken app rather than one that is still filling up, and most people who
 * close it then never open it again.
 */
@Composable
fun WelcomeScreen(songCount: Int, onStart: () -> Unit) {
    val gutter = rememberMetrics().gutter
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Accent.copy(alpha = 0.16f),
                        0.35f to Accent2.copy(alpha = 0.06f),
                        0.7f to androidx.compose.ui.graphics.Color.Transparent
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .padding(horizontal = gutter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RhythmMark(size = 64.dp)
            Spacer(Modifier.height(18.dp))
            Text(
                text = "ברוך הבא ל־Rhythm",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (songCount > 0) "נמצאו $songCount שירים במכשיר" else "מחפש שירים במכשיר",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(Modifier.height(26.dp))

            Step(
                icon = Icons.Filled.GraphicEq,
                title = "עכשיו מנתח את המוזיקה",
                body = "האפליקציה מאזינה לכל שיר ומודדת קצב, סולם ואנרגיה. " +
                    "זה רץ ברקע וייקח כמה דקות. הכל קורה על המכשיר, בלי אינטרנט."
            )
            Spacer(Modifier.height(14.dp))
            Step(
                icon = Icons.Filled.Star,
                title = "הפיד ילמד ממך",
                body = "לייקים, דירוגים ומה שאתה מדלג עליו הם מה שבונה את ההמלצות. " +
                    "בהתחלה הפיד דליל — הוא מתמלא ככל שאתה מאזין."
            )
            Spacer(Modifier.height(14.dp))
            Step(
                icon = Icons.Filled.Sell,
                title = "שירים שהורדת מהאינטרנט",
                body = "התגיות שלהם לרוב שגויות וכל השירים נראים כמו אמן אחד. " +
                    "בהגדרות יש תיקון אוטומטי שמסדר את זה."
            )

            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth()
            ) { Text("יאללה, בוא נתחיל") }
        }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, body: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.Top) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}
