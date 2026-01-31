package com.pira.ccloud.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pira.ccloud.BuildConfig
import com.pira.ccloud.R

@Composable
fun AboutScreen(navController: NavController?) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header with back button like Favorites screen
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController?.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            
            Text(
                text = stringResource(R.string.about),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            )
        }
        
        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Logo
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(R.drawable.splash_logo)
                    .crossfade(true)
                    .build(),
                contentDescription = "App Logo",
                modifier = Modifier.size(120.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // App Name
            Text(
                text = "CCloud",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // App Version and Architecture
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) - ${getArchitecture()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Developer Info
            Text(
                text = "Developed by Hossein Pira",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Links Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Connect with us",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    // GitHub Link
                    LinkItem(
                        icon = Icons.Default.Public,
                        text = "GitHub Repository",
                        url = "https://github.com/code3-dev/CCloud"
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Telegram Channel Link
                    LinkItem(
                        icon = Icons.Default.Send,
                        text = "Telegram Channel",
                        url = "https://t.me/irdevs_dns"
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Developer Telegram Link
                    LinkItem(
                        icon = Icons.Default.Send,
                        text = "Developer Telegram",
                        url = "https://t.me/h3dev"
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Developer Email Link
                    LinkItem(
                        icon = Icons.Default.Email,
                        text = "Developer Email",
                        url = "h3dev.pira@gmail.com",
                        isEmail = true
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Donation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Support Us",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    Text(
                        text = "If you find CCloud useful, consider supporting the development with a donation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    // Tron Address (TRX)
                    DonationItem(
                        coinName = "Tron (TRX)",
                        address = "TZDVavXKzfgpAX7NFVh8h2KY3XWCbNEyEe"
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Bitcoin Address (BTC)
                    DonationItem(
                        coinName = "Bitcoin (BTC)",
                        address = "bc1q74l58mhmtwgk4ygr3l0zyv53fgxdajp28tvrld"
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Copyright
            Text(
                text = "© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} CCloud. All rights reserved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun LinkItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    url: String,
    isEmail: Boolean = false
) {
    val context = LocalContext.current
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                try {
                    val intent = if (isEmail) {
                        Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:$url")
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Handle error if needed
                }
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Open link",
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer(rotationZ = 180f)
        )
    }
}

@Composable
fun DonationItem(
    coinName: String,
    address: String
) {
    val context = LocalContext.current
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Copy address to clipboard
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("$coinName Address", address)
                clipboard.setPrimaryClip(clip)
                
                // Show toast
                android.widget.Toast.makeText(context, "$coinName address copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = coinName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy address",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
    }
}

fun getArchitecture(): String {
    return try {
        // Get the device's primary ABI
        val supportedAbis = android.os.Build.SUPPORTED_ABIS
        if (supportedAbis.isNotEmpty()) {
            when (supportedAbis[0]) {
                "arm64-v8a" -> "ARM64 (arm64-v8a)"
                "armeabi-v7a" -> "ARM32 (armeabi-v7a)"
                "x86_64" -> "x86_64"
                "x86" -> "x86"
                else -> "${supportedAbis[0]} (Unknown)"
            }
        } else {
            "Unknown"
        }
    } catch (e: Exception) {
        "Unknown"
    }
}