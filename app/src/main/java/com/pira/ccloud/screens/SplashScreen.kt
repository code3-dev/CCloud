package com.pira.ccloud.screens

import android.content.Context
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pira.ccloud.BuildConfig
import com.pira.ccloud.R
import com.pira.ccloud.utils.StorageUtils
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onTimeout: () -> Unit,
    backgroundColor: Color
) {
    val context = LocalContext.current
    var showWelcomeSlider by remember { mutableStateOf(!StorageUtils.isWelcomeCompleted(context)) }
    var currentSlide by remember { mutableStateOf(0) }
    
    if (showWelcomeSlider) {
        WelcomeSliderScreen(
            currentSlide = currentSlide,
            onSlideChange = { currentSlide = it },
            onFinished = {
                StorageUtils.saveWelcomeCompleted(context)
                showWelcomeSlider = false
            },
            backgroundColor = backgroundColor
        )
    } else {
        // Original splash screen
        LaunchedEffect(Unit) {
            // Wait for some time before navigating
            delay(2000)
            onTimeout()
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
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
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "CCloud",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Version ${BuildConfig.VERSION_NAME ?: "1.0"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "Developed by Hossein Pira",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun WelcomeSliderScreen(
    currentSlide: Int,
    onSlideChange: (Int) -> Unit,
    onFinished: () -> Unit,
    backgroundColor: Color
) {
    // Welcome slides data
    val slides = listOf(
        SlideData(R.drawable.s1, "Welcome to CCloud"),
        SlideData(R.drawable.s2, "Free & Fast"),
        SlideData(R.drawable.s3, "No Login Required"),
        SlideData(R.drawable.s4, "For Iranian Users")
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Current slide
        SlideItem(
            slide = slides[currentSlide],
            backgroundColor = backgroundColor
        )
        
        // Bottom navigation controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .fillMaxWidth()
        ) {
            // Page indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == currentSlide) Color.Yellow
                                else Color.Gray.copy(alpha = 0.5f)
                            )
                    )
                }
            }
            
            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Previous button
                if (currentSlide > 0) {
                    Button(
                        onClick = {
                            onSlideChange(currentSlide - 1)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Previous"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Previous")
                    }
                } else {
                    Spacer(modifier = Modifier.size(1.dp))
                }
                
                // Next/Finish button
                Button(
                    onClick = {
                        if (currentSlide < 3) {
                            onSlideChange(currentSlide + 1)
                        } else {
                            onFinished()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Yellow
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = if (currentSlide < 3) "Next" else "Get Started",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = if (currentSlide < 3) "Next" else "Get Started",
                        tint = Color.Black
                    )
                }
            }
        }
    }
}

data class SlideData(
    val imageRes: Int,
    val title: String
)

@Composable
fun SlideItem(
    slide: SlideData,
    backgroundColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Background image
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(slide.imageRes)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        
        // Overlay with yellow text
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
        )
        
        // Center text
        Text(
            text = slide.title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.Yellow,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp)
        )
    }
}