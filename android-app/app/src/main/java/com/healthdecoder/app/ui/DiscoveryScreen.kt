package com.healthdecoder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.model.DiscoveryFacility
import com.healthdecoder.app.model.DiscoverySearchRequest
import com.healthdecoder.app.model.UhiProvider
import com.healthdecoder.app.network.NetworkModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class SimulatedLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

private val locations = listOf(
    SimulatedLocation("Pune (Erandawane / Deccan)", 18.5089, 73.8378),
    SimulatedLocation("Mumbai (Bandra)", 19.0596, 72.8295),
    SimulatedLocation("Delhi (Connaught Place)", 28.6304, 77.2177),
    SimulatedLocation("Bangalore (Indiranagar)", 12.9719, 77.6412)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    initialCategory: String = "lab_tests",
    initialQuery: String? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTabIndex by remember { 
        mutableStateOf(
            when (initialCategory) {
                "hospitals" -> 0
                "lab_tests" -> 1
                "doctors" -> 2
                else -> 1
            }
        )
    }

    val categories = listOf("hospitals", "lab_tests", "doctors")
    val categoryLabels = listOf("Hospitals", "Lab Tests", "Doctors")
    val categoryEmojis = listOf("🏥", "🧪", "🩺")

    var searchQuery by remember { mutableStateOf(initialQuery ?: "") }
    var selectedLocation by remember { mutableStateOf(locations[0]) }
    var locationDropdownExpanded by remember { mutableStateOf(false) }

    // Mode: "commercial" or "uhi"
    var selectedMode by remember { mutableStateOf("uhi") }

    var selectedDistanceLimit by remember { mutableStateOf(50.0) } // Default 50 km

    // Results state
    var commercialResults by remember { mutableStateOf<List<DiscoveryFacility>>(emptyList()) }
    var uhiResults by remember { mutableStateOf<List<UhiProvider>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var uhiSearchId by remember { mutableStateOf<String?>(null) }
    var uhiStatusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    // Booking Dialog
    var showBookingSuccessDialog by remember { mutableStateOf(false) }
    var bookedItemName by remember { mutableStateOf("") }
    var bookedFacilityName by remember { mutableStateOf("") }
    var selectedSlots = remember { mutableStateMapOf<String, String>() }

    fun triggerSearch() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = ""
            commercialResults = emptyList()
            uhiResults = emptyList()
            uhiSearchId = null
            uhiStatusMessage = ""

            try {
                val api = NetworkModule.getApi(context)
                val category = categories[selectedTabIndex]

                if (selectedMode == "commercial") {
                    val req = DiscoverySearchRequest(
                        latitude = selectedLocation.latitude,
                        longitude = selectedLocation.longitude,
                        category = category,
                        query = searchQuery.ifBlank { null },
                        mode = "commercial"
                    )
                    val res = api.searchDiscovery(req)
                    commercialResults = res.results
                } else {
                    // UHI Asynchronous route
                    val req = DiscoverySearchRequest(
                        latitude = selectedLocation.latitude,
                        longitude = selectedLocation.longitude,
                        category = category,
                        query = searchQuery.ifBlank { null },
                        mode = "uhi"
                    )
                    uhiStatusMessage = "Broadcasting search to UHI gateway..."
                    val res = api.searchDiscovery(req)
                    
                    val searchId = res.searchId
                    if (searchId != null && res.gatewayStatus == "ACK") {
                        uhiSearchId = searchId
                        uhiStatusMessage = "Gateway ACK received. Listening for provider webhook responses..."
                        
                        // Poll 4 times with a 1.5s delay to simulate the real-time arrival of webhook pushes
                        for (poll in 1..4) {
                            delay(1500)
                            uhiStatusMessage = "Listening for provider webhooks... (Incoming responses)"
                            val pollRes = api.getUhiResults(searchId)
                            uhiResults = pollRes.results
                        }
                        uhiStatusMessage = "Search complete. Received responses from ${uhiResults.size} UHI clinics/hospitals."
                    } else {
                        errorMessage = res.message ?: "UHI search request was not acknowledged by the gateway."
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Search failed. Make sure the backend server is running and database is migrated."
            } finally {
                isLoading = false
            }
        }
    }

    // Trigger initial search if coming contextually from pending tests
    LaunchedEffect(selectedTabIndex) {
        triggerSearch()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(tr("Find Care"), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Category Tabs
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
            ) {
                categoryLabels.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { 
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(categoryEmojis[index], fontSize = 16.sp)
                                Text(tr(label), fontWeight = FontWeight.Medium)
                            }
                        }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { 
                        Text(
                            when (selectedTabIndex) {
                                0 -> tr("Search hospital name...")
                                1 -> tr("e.g. CBC, Lipid, Thyroid...")
                                else -> tr("Search doctor or specialty...")
                            }
                        ) 
                    },
                    leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(imageVector = Icons.Default.Clear, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Location selection
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedCard(
                            onClick = { locationDropdownExpanded = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = selectedLocation.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }
                        DropdownMenu(
                            expanded = locationDropdownExpanded,
                            onDismissRequest = { locationDropdownExpanded = false }
                        ) {
                            locations.forEach { loc ->
                                DropdownMenuItem(
                                    text = { Text(loc.name) },
                                    onClick = {
                                        selectedLocation = loc
                                        locationDropdownExpanded = false
                                        triggerSearch()
                                    }
                                )
                            }
                        }
                    }

                    val gpsToastText = tr("Fetching location from GPS...")
                    // GPS Button
                    IconButton(
                        onClick = {
                            selectedLocation = SimulatedLocation("Current Location (GPS)", 18.5089, 73.8378)
                            android.widget.Toast.makeText(context, gpsToastText, android.widget.Toast.LENGTH_SHORT).show()
                            triggerSearch()
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = tr("Use GPS"),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Search Button
                    Button(
                        onClick = { triggerSearch() },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(tr("Search"), fontWeight = FontWeight.Bold)
                    }
                }

                // Distance Limit selector (10km / 20km / 50km / All)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = tr("Within:"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    val distanceOptions = listOf(10.0, 20.0, 50.0, 999.0)
                    val distanceLabels = listOf("10 km", "20 km", "50 km", "Any")
                    
                    distanceOptions.forEachIndexed { idx, dist ->
                        val isSelected = selectedDistanceLimit == dist
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { selectedDistanceLimit = dist }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = tr(distanceLabels[idx]),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // UHI Information Banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = tr("Searching Unified Health Interface (UHI) registry for real-time open network listings."),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Purely Informational Disclaimer Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFC62828),
                            modifier = Modifier.size(18.dp).padding(top = 2.dp)
                        )
                        Column {
                            Text(
                                text = tr("IMPORTANT DISCLAIMER"),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC62828)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tr("All search results, doctor availability, and diagnostic slots are purely informational. Sourced from the public UHI network, they are not clinical recommendations or confirmed appointments. Do not rely solely on this information. Always consult a qualified medical professional for health concerns."),
                                fontSize = 10.sp,
                                color = Color(0xFF37474F),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            // Status message for UHI
            if (selectedMode == "uhi" && uhiStatusMessage.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = tr(uhiStatusMessage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Error display
            if (errorMessage.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = tr(errorMessage),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Results Container
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isLoading && commercialResults.isEmpty() && uhiResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = if (selectedMode == "uhi") tr("Waiting for UHI webhook callbacks...") else tr("Searching nearby centers..."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    if (selectedMode == "commercial") {
                        if (commercialResults.isEmpty()) {
                            EmptyResultsView(categories[selectedTabIndex])
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(commercialResults.filter { parseDistanceKm(it.distance) <= selectedDistanceLimit }, key = { it.id }) { facility ->
                                    CommercialResultCard(
                                        category = categories[selectedTabIndex],
                                        facility = facility,
                                        selectedSlot = selectedSlots[facility.id],
                                        onSelectSlot = { slot -> selectedSlots[facility.id] = slot },
                                        onBookClick = { itemName ->
                                            bookedItemName = itemName
                                            bookedFacilityName = facility.name
                                            showBookingSuccessDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // UHI Results (Mapped from UhiProvider payload)
                        if (uhiResults.isEmpty() && !isLoading) {
                            EmptyResultsView(categories[selectedTabIndex])
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(uhiResults.filter { parseDistanceKm(it.distance) <= selectedDistanceLimit }, key = { it.providerId }) { provider ->
                                    UhiResultCard(
                                        provider = provider,
                                        selectedSlot = selectedSlots[provider.providerId],
                                        onSelectSlot = { slot -> selectedSlots[provider.providerId] = slot },
                                        onBookClick = { itemName ->
                                            bookedItemName = itemName
                                            bookedFacilityName = provider.providerName
                                            showBookingSuccessDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Booking Confirmation Dialog
        if (showBookingSuccessDialog) {
            AlertDialog(
                onDismissRequest = { showBookingSuccessDialog = false },
                icon = { Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(40.dp)) },
                title = { Text(tr("Booking Request Sent"), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "${tr("Successfully initiated booking request for:")} $bookedItemName",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${tr("Facility:")} $bookedFacilityName",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val slot = selectedSlots[bookedFacilityName.hashCode().toString()] ?: selectedSlots.values.firstOrNull()
                        if (slot != null) {
                            Text(
                                text = "${tr("Scheduled Slot:")} $slot",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = tr("The facility will confirm your slot via SMS/WhatsApp within 10 minutes."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { showBookingSuccessDialog = false }) {
                        Text(tr("OK"))
                    }
                }
            )
        }
    }
}

@Composable
private fun EmptyResultsView(category: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = tr("No facilities found"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when (category) {
                    "hospitals" -> tr("No hospitals matching your search around this area.")
                    "lab_tests" -> tr("No diagnostic labs found offering this test in this area.")
                    else -> tr("No clinics or doctors matching your query around this coordinates.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CommercialResultCard(
    category: String,
    facility: DiscoveryFacility,
    selectedSlot: String?,
    onSelectSlot: (String) -> Unit,
    onBookClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = facility.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = facility.address ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = facility.distance ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
                        Text(text = facility.rating ?: "4.0", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Specialty / Content based on Category
            if (category == "hospitals") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (facility.hasEmergency) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFFFEBEE))
                                .border(1.dp, Color(0xFFEF5350), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = tr("24/7 Emergency"), color = Color(0xFFC62828), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = { /* Simulated call */ }) {
                        Icon(imageVector = Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = facility.phone ?: tr("Call"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } 
            
            else if (category == "lab_tests") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (facility.homeCollection) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFE8F5E9))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(text = tr("Home Sample Collection"), color = Color(0xFF2E7D32), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    facility.matchedTests.forEach { test ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = test.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                if (test.timeToReport != null) {
                                    Text(text = "${tr("Report in:")} ${test.timeToReport}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Button(
                                onClick = { onBookClick(test.name) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("₹${test.price.toInt()} - " + tr("Book"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } 
            
            else if (category == "doctors") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.MedicalServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text(
                            text = "${facility.specialty} (${facility.experience})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = "${tr("Consultation Fee:")} ₹${facility.fee?.toInt()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )

                    // Slots selection
                    Text(tr("Available Slots Today:"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        facility.slots.take(4).forEach { slot ->
                            val isSelected = selectedSlot == slot
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .clickable { onSelectSlot(slot) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = slot,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { onBookClick(facility.name) },
                        enabled = selectedSlot != null,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(36.dp)
                    ) {
                        Text(if (selectedSlot != null) tr("Book Appointment") else tr("Select a Slot first"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun UhiResultCard(
    provider: UhiProvider,
    selectedSlot: String?,
    onSelectSlot: (String) -> Unit,
    onBookClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = provider.providerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFE8F5E9))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(text = "UHI", color = Color(0xFF2E7D32), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        text = provider.address ?: "${tr("UHI Registered")} ${provider.type.capitalize()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = provider.distance ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
                        Text(text = provider.rating ?: "4.5", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (provider.homeCollection) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE3F2FD))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = tr("Home Sample Collection"), color = Color(0xFF1565C0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Render Items pushed from the provider
            provider.items.forEach { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            // Specialty or other details
                            val specialty = item.descriptor?.get("specialty")
                            val exp = item.descriptor?.get("experience")
                            val tat = item.descriptor?.get("timeToReport")
                            if (specialty != null) {
                                Text(text = "$specialty ${exp?.let { "($it)" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (tat != null) {
                                Text(text = "${tr("Report in:")} $tat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        val priceVal = item.price?.value ?: 0.0
                        Button(
                            onClick = { onBookClick(item.name) },
                            enabled = item.slots.isEmpty() || selectedSlot != null,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("₹${priceVal.toInt()} - " + tr("Book"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Slots if present (e.g. for doctor teleconsult / physical visit)
                    if (item.slots.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            item.slots.forEach { slot ->
                                val isSelected = selectedSlot == slot
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                        .clickable { onSelectSlot(slot) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = slot,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseDistanceKm(distanceStr: String?): Double {
    if (distanceStr == null) return 0.0
    // e.g. "2.4 km", "15.0 km", "0.8 km"
    val clean = distanceStr.replace("km", "").replace(" ", "").trim()
    return clean.toDoubleOrNull() ?: 0.0
}
