package com.pira.ccloud.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.pira.ccloud.R
import com.pira.ccloud.data.model.FavoriteGroup
import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.navigation.AppScreens
import com.pira.ccloud.utils.StorageUtils
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(navController: NavController) {
    var favorites by remember { mutableStateOf<List<FavoriteItem>>(emptyList()) }
    var groups by remember { mutableStateOf<List<FavoriteGroup>>(emptyList()) }
    var selectedGroup by remember { mutableStateOf<FavoriteGroup?>(null) }
    val context = LocalContext.current
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showMoveToGroupDialog by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<FavoriteItem?>(null) }
    
    // Focus requesters for handling TV remote navigation
    val focusRequester = remember { FocusRequester() }
    val groupCardFocusRequester = remember { FocusRequester() }
    val favoritesCardFocusRequester = remember { FocusRequester() }
    
    // Load favorites and groups when screen is displayed
    LaunchedEffect(Unit) {
        favorites = StorageUtils.loadAllFavorites(context)
        groups = StorageUtils.loadAllFavoriteGroups(context)
        selectedGroup = groups.firstOrNull { it.isDefault } ?: groups.firstOrNull()
    }
    
    // Confirmation dialog for deleting all favorites
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete All Favorites") },
            text = { Text("Are you sure you want to delete all favorites? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        StorageUtils.clearAllFavorites(context)
                        favorites = emptyList()
                        showDeleteAllDialog = false
                        // Show toast
                        android.widget.Toast.makeText(context, "All favorites deleted", android.widget.Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Dialog for creating a new group
    if (showCreateGroupDialog) {
        var groupName by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showCreateGroupDialog = false },
            title = { Text("Create New Playlist") },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Playlist Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, // Prevent new lines
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (groupName.isNotBlank()) {
                                // Check if group with same name already exists
                                val existingGroup = groups.find { 
                                    it.name.equals(groupName, ignoreCase = true) && !it.isDefault
                                }
                                
                                if (existingGroup == null) {
                                    val newGroup = FavoriteGroup(
                                        id = UUID.randomUUID().toString(),
                                        name = groupName,
                                        isDefault = false
                                    )
                                    StorageUtils.saveFavoriteGroup(context, newGroup)
                                    groups = StorageUtils.loadAllFavoriteGroups(context)
                                    showCreateGroupDialog = false
                                    groupName = ""
                                    // Show success message
                                    android.widget.Toast.makeText(
                                        context, 
                                        "Playlist created successfully", 
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    // Show error message
                                    android.widget.Toast.makeText(
                                        context, 
                                        "A playlist with this name already exists", 
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (groupName.isNotBlank()) {
                            // Check if group with same name already exists
                            val existingGroup = groups.find { 
                                it.name.equals(groupName, ignoreCase = true) && !it.isDefault
                            }
                            
                            if (existingGroup == null) {
                                val newGroup = FavoriteGroup(
                                    id = UUID.randomUUID().toString(),
                                    name = groupName,
                                    isDefault = false
                                )
                                StorageUtils.saveFavoriteGroup(context, newGroup)
                                groups = StorageUtils.loadAllFavoriteGroups(context)
                                showCreateGroupDialog = false
                                groupName = ""
                                // Show success message
                                android.widget.Toast.makeText(
                                    context, 
                                    "Playlist created successfully", 
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                // Show error message
                                android.widget.Toast.makeText(
                                    context, 
                                    "A playlist with this name already exists", 
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    enabled = groupName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreateGroupDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Dialog for renaming a group
    var showRenameGroupDialog by remember { mutableStateOf(false) }
    var groupToRename by remember { mutableStateOf<FavoriteGroup?>(null) }
    var newGroupName by remember { mutableStateOf("") }
    
    if (showRenameGroupDialog && groupToRename != null) {
        AlertDialog(
            onDismissRequest = { showRenameGroupDialog = false },
            title = { Text("Rename Playlist") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("New Playlist Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (newGroupName.isNotBlank() && groupToRename != null) {
                                // Check if another group with same name already exists
                                val existingGroup = groups.find { 
                                    it.id != groupToRename!!.id && 
                                    it.name.equals(newGroupName, ignoreCase = true) && 
                                    !it.isDefault
                                }
                                
                                if (existingGroup == null) {
                                    val updatedGroup = groupToRename!!.copy(name = newGroupName)
                                    StorageUtils.saveFavoriteGroup(context, updatedGroup)
                                    groups = StorageUtils.loadAllFavoriteGroups(context)
                                    showRenameGroupDialog = false
                                    groupToRename = null
                                    newGroupName = ""
                                    // Show success message
                                    android.widget.Toast.makeText(
                                        context, 
                                        "Playlist renamed successfully", 
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    // Show error message
                                    android.widget.Toast.makeText(
                                        context, 
                                        "A playlist with this name already exists", 
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newGroupName.isNotBlank() && groupToRename != null) {
                            // Check if another group with same name already exists
                            val existingGroup = groups.find { 
                                it.id != groupToRename!!.id && 
                                it.name.equals(newGroupName, ignoreCase = true) && 
                                !it.isDefault
                            }
                            
                            if (existingGroup == null) {
                                val updatedGroup = groupToRename!!.copy(name = newGroupName)
                                StorageUtils.saveFavoriteGroup(context, updatedGroup)
                                groups = StorageUtils.loadAllFavoriteGroups(context)
                                showRenameGroupDialog = false
                                groupToRename = null
                                newGroupName = ""
                                // Show success message
                                android.widget.Toast.makeText(
                                    context, 
                                    "Playlist renamed successfully", 
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                // Show error message
                                android.widget.Toast.makeText(
                                    context, 
                                    "A playlist with this name already exists", 
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    enabled = newGroupName.isNotBlank()
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showRenameGroupDialog = false
                        groupToRename = null
                        newGroupName = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Dialog for deleting a group
    var showDeleteGroupDialog by remember { mutableStateOf(false) }
    var groupToDelete by remember { mutableStateOf<FavoriteGroup?>(null) }
    
    if (showDeleteGroupDialog && groupToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteGroupDialog = false },
            title = { Text("Delete Playlist") },
            text = { 
                Text("Are you sure you want to delete the playlist \"${groupToDelete!!.name}\"? This action cannot be undone.") 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (groupToDelete != null) {
                            StorageUtils.removeFavoriteGroup(context, groupToDelete!!.id)
                            groups = StorageUtils.loadAllFavoriteGroups(context)
                            
                            // If the deleted group was the selected group, switch to default
                            if (selectedGroup?.id == groupToDelete!!.id) {
                                selectedGroup = groups.firstOrNull { it.isDefault }
                                favorites = StorageUtils.loadAllFavorites(context)
                            }
                            
                            showDeleteGroupDialog = false
                            groupToDelete = null
                            // Show success message
                            android.widget.Toast.makeText(
                                context, 
                                "Playlist deleted successfully", 
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showDeleteGroupDialog = false
                        groupToDelete = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Dialog for moving item to groups (multi-select)
    if (showMoveToGroupDialog && selectedItem != null) {
        val item = selectedItem!!
        var selectedGroups by remember { mutableStateOf<List<String>>(emptyList()) }
        
        // Initialize selected groups
        LaunchedEffect(item) {
            val currentGroups = StorageUtils.getGroupsForFavorite(context, item.id, item.type)
            selectedGroups = currentGroups.map { it.id }
        }
        
        AlertDialog(
            onDismissRequest = { showMoveToGroupDialog = false },
            title = { Text("Select Playlists") },
            text = {
                LazyColumn {
                    items(groups.filter { !it.isDefault }) { group ->
                        val isChecked = selectedGroups.contains(group.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedGroups = if (isChecked) {
                                        selectedGroups.filter { it != group.id }
                                    } else {
                                        selectedGroups + group.id
                                    }
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedGroups = if (checked) {
                                        selectedGroups + group.id
                                    } else {
                                        selectedGroups.filter { it != group.id }
                                    }
                                }
                            )
                            Text(
                                text = group.name,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Update group memberships
                        groups.filter { !it.isDefault }.forEach { group ->
                            if (selectedGroups.contains(group.id)) {
                                // Add to group if not already there
                                if (!StorageUtils.isFavoriteInGroup(context, group.id, item.id, item.type)) {
                                    StorageUtils.addFavoriteToGroup(context, group.id, item.id, item.type)
                                }
                            } else {
                                // Remove from group if it was there
                                if (StorageUtils.isFavoriteInGroup(context, group.id, item.id, item.type)) {
                                    StorageUtils.removeFavoriteFromGroup(context, group.id, item.id, item.type)
                                }
                            }
                        }
                        
                        // Refresh data
                        groups = StorageUtils.loadAllFavoriteGroups(context)
                        favorites = if (selectedGroup != null && !selectedGroup!!.isDefault) {
                            StorageUtils.getFavoritesInGroup(context, selectedGroup!!.id)
                        } else {
                            StorageUtils.loadAllFavorites(context)
                        }
                        
                        showMoveToGroupDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showMoveToGroupDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Top App Bar
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(animationSpec = tween(300)) + slideInVertically(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)) + slideOutVertically(animationSpec = tween(300))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedGroup?.name ?: stringResource(R.string.favorites),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                
                // Create group button
                IconButton(
                    onClick = { showCreateGroupDialog = true },
                    modifier = Modifier
                        .size(48.dp)
                        .focusable()
                        .focusRequester(remember { FocusRequester() })
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create Playlist",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                // Delete all button (only show if there are favorites)
                if (favorites.isNotEmpty()) {
                    IconButton(
                        onClick = { showDeleteAllDialog = true },
                        modifier = Modifier
                            .size(48.dp)
                            .focusable()
                            .focusRequester(remember { FocusRequester() })
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete All",
                            tint = MaterialTheme.colorScheme.primary
                    )
                    }
                }
            }
        }
        
        // Group selector
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(animationSpec = tween(400)) + slideInVertically(animationSpec = tween(400, delayMillis = 100)),
            exit = fadeOut(animationSpec = tween(400)) + slideOutVertically(animationSpec = tween(400))
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .focusable()
                    .focusRequester(groupCardFocusRequester)
                    .focusProperties {
                        down = favoritesCardFocusRequester
                    },
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Playlists",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .weight(1f)
                        )
                    }
                    
                    // Display groups as selectable chips
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        items(groups) { group ->
                            Box {
                                var showGroupMenu by remember { mutableStateOf(false) }
                                
                                Card(
                                    modifier = Modifier
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            selectedGroup = group
                                            // Load favorites for this group
                                            favorites = if (group.isDefault) {
                                                StorageUtils.loadAllFavorites(context)
                                            } else {
                                                StorageUtils.getFavoritesInGroup(context, group.id)
                                            }
                                        }
                                        .focusable()
                                        .onKeyEvent { keyEvent ->
                                            when (keyEvent.key) {
                                                Key.Enter, Key.Spacebar -> {
                                                    selectedGroup = group
                                                    // Load favorites for this group
                                                    favorites = if (group.isDefault) {
                                                        StorageUtils.loadAllFavorites(context)
                                                    } else {
                                                        StorageUtils.getFavoritesInGroup(context, group.id)
                                                    }
                                                    true // Handled
                                                }
                                                else -> false // Let default handling occur
                                            }
                                        },
                                    shape = RoundedCornerShape(16.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = if (selectedGroup?.id == group.id) 4.dp else 0.dp),
                                    colors = if (selectedGroup?.id == group.id) {
                                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    } else {
                                        CardDefaults.cardColors()
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = group.name)
                                        
                                        // Show menu icon for non-default groups
                                        if (!group.isDefault) {
                                            IconButton(
                                                onClick = { showGroupMenu = true },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.MoreVert,
                                                    contentDescription = "Group options",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                
                                // Group menu for rename/delete options
                                DropdownMenu(
                                    expanded = showGroupMenu,
                                    onDismissRequest = { showGroupMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        onClick = {
                                            groupToRename = group
                                            newGroupName = group.name
                                            showRenameGroupDialog = true
                                            showGroupMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete") },
                                        onClick = {
                                            groupToDelete = group
                                            showDeleteGroupDialog = true
                                            showGroupMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Content
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(animationSpec = tween(500)) + slideInVertically(animationSpec = tween(500, delayMillis = 200)),
            exit = fadeOut(animationSpec = tween(500)) + slideOutVertically(animationSpec = tween(500))
        ) {
            if (favorites.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .padding(bottom = 16.dp)
                        )
                        Text(text = "No favorites yet")
                    }
                }
            } else {
                // State variables for the confirmation dialog
                var showRemoveFavoriteDialog by remember { mutableStateOf(false) }
                var favoriteToRemove by remember { mutableStateOf<FavoriteItem?>(null) }
                
                // Confirmation dialog for removing from favorites
                if (showRemoveFavoriteDialog && favoriteToRemove != null) {
                    AlertDialog(
                        onDismissRequest = { showRemoveFavoriteDialog = false },
                        title = { Text("Remove from Favorites") },
                        text = { Text("Are you sure you want to remove \"${favoriteToRemove!!.title}\" from your favorites?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    StorageUtils.removeFavorite(context, favoriteToRemove!!.id, favoriteToRemove!!.type)
                                    // Refresh the favorites list
                                    favorites = if (selectedGroup != null && !selectedGroup!!.isDefault) {
                                        StorageUtils.getFavoritesInGroup(context, selectedGroup!!.id)
                                    } else {
                                        StorageUtils.loadAllFavorites(context)
                                    }
                                    showRemoveFavoriteDialog = false
                                    favoriteToRemove = null
                                    // Show toast
                                    android.widget.Toast.makeText(context, "Removed from favorites", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Remove")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { 
                                    showRemoveFavoriteDialog = false
                                    favoriteToRemove = null
                                }
                            ) {
                                Text("Cancel")
                            }
                        }
                    )
                }
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .weight(1f)
                        .focusable()
                        .focusRequester(favoritesCardFocusRequester)
                        .focusProperties {
                            up = groupCardFocusRequester
                        },
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {                     
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(0.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
                        ) {
                            items(favorites) { favorite ->
                                FavoriteItemCard(
                                    favorite = favorite,
                                    onClick = {
                                        // Save the favorite to the appropriate database before navigating
                                        StorageUtils.saveFavoriteToDatabase(context, favorite)
                                        
                                        // Navigate to the appropriate screen based on type
                                        when (favorite.type) {
                                            "movie" -> {
                                                navController.navigate("${AppScreens.SingleMovie.route.replace("{movieId}", favorite.id.toString())}")
                                            }
                                            "series" -> {
                                                navController.navigate("${AppScreens.SingleSeries.route.replace("{seriesId}", favorite.id.toString())}")
                                            }
                                        }
                                    },
                                    onDelete = {
                                        favoriteToRemove = favorite
                                        showRemoveFavoriteDialog = true
                                    },
                                    onMoveToGroup = { item ->
                                        selectedItem = item
                                        showMoveToGroupDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FavoriteItemCard(
    favorite: FavoriteItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMoveToGroup: (FavoriteItem) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .focusable()
            .focusRequester(focusRequester)
            .onKeyEvent { keyEvent ->
                when (keyEvent.key) {
                    Key.Enter, Key.Spacebar -> {
                        onClick()
                        true // Handled
                    }
                    else -> false // Let default handling occur
                }
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Poster image
            Image(
                painter = rememberAsyncImagePainter(
                    ImageRequest.Builder(LocalContext.current)
                        .data(favorite.image)
                        .crossfade(true)
                        .build()
                ),
                contentDescription = favorite.title,
                modifier = Modifier
                    .height(80.dp)
                    .width(60.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            
            // Title and details
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = favorite.title,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                // Show type and year
                Text(
                    text = "${favorite.type.capitalize()} • ${favorite.year}",
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 4.dp),
                        tint = androidx.compose.ui.graphics.Color.Red
                    )
                    Text(
                        text = String.format("%.1f", favorite.imdb)
                    )
                }
            }
            
            // Menu button for additional actions
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.focusable()
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options"
                    )
                }
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Move to Playlist") },
                        onClick = {
                            onMoveToGroup(favorite)
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}