package dev.siliconoptimizer.buddy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** An honest first-run screen: explains the connection before asking for one. */
@Composable
fun WelcomeCard(onPair: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("YOUR PRIVATE AI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Your AI.\nWithin reach.", style = MaterialTheme.typography.displaySmall)
            Text(
                "Bring your Mac's intelligence along. Chat, create, and check in from wherever you are.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF143D39), RoundedCornerShape(28.dp)).padding(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Computer, null, tint = Color(0xFFBDF4DF), modifier = Modifier.size(56.dp))
                Text("Your Mac", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF53887A))
            Icon(Icons.Filled.Wifi, null, tint = Color(0xFFBDF4DF), modifier = Modifier.size(22.dp))
            HorizontalDivider(modifier = Modifier.weight(1f), color = Color(0xFF53887A))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.PhoneAndroid, null, tint = Color(0xFFBDF4DF), modifier = Modifier.size(48.dp))
                Text("With you", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
        Button(onClick = onPair, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("Pair with a Mac", modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        SectionCard("A little closer to your Mac", Icons.Filled.AutoAwesome) {
            WelcomeFeature(Icons.AutoMirrored.Filled.Chat, "Pick up a conversation", "Ask the models you already run.")
            WelcomeFeature(Icons.Filled.AutoAwesome, "Make something new", "Start images, videos, and 3D jobs.")
            WelcomeFeature(Icons.Filled.Wifi, "Keep it on your network", "Connect directly over your own tailnet.")
        }
    }
}

@Composable
private fun WelcomeFeature(icon: ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
