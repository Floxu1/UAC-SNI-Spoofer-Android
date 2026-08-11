package com.uacspoofer.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.ui.theme.UacColors

@Composable
internal fun CountryFlagIcon(
    country: CountryMetadata,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val shape = RoundedCornerShape(5.dp)
    val flagDrawable = CountryFlagResources.drawableFor(country.countryCode)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.055f), shape)
            .semantics { contentDescription = country.countryName },
        contentAlignment = Alignment.Center,
    ) {
        if (flagDrawable != null) {
            Image(
                painter = painterResource(flagDrawable),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                Icons.Outlined.Public,
                contentDescription = null,
                tint = UacColors.TextSecondary,
                modifier = Modifier.size(size * 0.72f),
            )
        }
    }
}
