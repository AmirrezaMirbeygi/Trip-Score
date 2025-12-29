# Trip Score

Trip Score is an Android application that automatically tracks and scores your driving performance. The app monitors various driving behaviors including speed, braking, acceleration, cornering, and phone usage to provide a comprehensive 0-5 star rating for each trip.

## Features

- **Automatic Trip Detection**: Automatically detects when you start and stop driving
- **Real-time Scoring**: Live 0-5 star rating system based on driving performance
- **Comprehensive Event Tracking**:
  - Speeding detection with dynamic thresholds
  - Braking analysis (minor, mid, major severity)
  - Acceleration monitoring
  - Cornering detection
  - Phone distraction tracking
- **Trip History**: View past trips with detailed statistics and maps
- **Interactive Maps**: Visualize trip routes with event markers
- **Background Recording**: Continues tracking even when the app is in the background
- **Low-pass Filtering**: GPS data is filtered to reduce noise and improve accuracy

## Architecture

The app follows a clean architecture pattern with the following components:

### Core Components

- **TripRecorderService**: Foreground service that handles location tracking, trip detection, and event monitoring
- **TripScorer**: Core logic for scoring trips and detecting driving events
- **Room Database**: Local storage for trips, routes, location points, and event markers
- **Jetpack Compose UI**: Modern declarative UI framework

### Key Technologies

- **Kotlin**: Primary programming language
- **Jetpack Compose**: UI framework
- **Room**: Local database
- **Google Maps SDK**: Map visualization
- **Location Services**: GPS tracking
- **Coroutines & Flow**: Asynchronous programming and reactive data streams

## Project Structure

```
app/src/main/java/com/goofyapps/tripscore/
├── ui/                    # UI components (Compose screens)
│   ├── HomeScreen.kt      # Main screen with recording controls
│   ├── ActiveTripScreen.kt # Live trip view
│   ├── TripsScreen.kt     # Trip history list
│   ├── TripDetailScreen.kt # Detailed trip view with map
│   └── AppNav.kt          # Navigation setup
├── service/                # Background services
│   ├── TripRecorderService.kt # Main recording service
│   └── TripServiceController.kt # Service lifecycle management
├── data/                   # Data layer
│   ├── TripEntity.kt       # Trip data model
│   ├── RouteEntity.kt      # Route data model
│   ├── AppDatabase.kt     # Room database
│   └── CurrentTripState.kt # Live trip state
├── logic/                  # Business logic
│   └── TripScorer.kt       # Scoring and event detection
└── vm/                     # ViewModels
    ├── TripsViewModel.kt   # Trip list view model
    └── TripDetailViewModel.kt # Trip detail view model
```

## Requirements

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 26 (Android 8.0) or higher
- Target SDK: 35
- Kotlin 1.9+

## Setup

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd TripScore
   ```

2. **Open in Android Studio**
   - Open Android Studio
   - Select "Open an Existing Project"
   - Navigate to the TripScore directory

3. **Configure secrets** (if needed)
   - Copy `secrets.properties.example` to `secrets.properties`
   - Add any required configuration values

4. **Sync Gradle**
   - Android Studio should automatically sync Gradle
   - If not, click "Sync Now" when prompted

5. **Build and Run**
   - Connect an Android device or start an emulator
   - Click "Run" or press `Shift+F10`

## Building

### Debug Build
```bash
./gradlew assembleDebug
```

### Release Build
```bash
./gradlew assembleRelease
```

The APK will be generated in `app/build/outputs/apk/` or AAB in `app/build/outputs/bundle/`.

## Permissions

The app requires the following permissions:

- **Location (Fine & Coarse)**: Required for GPS tracking
- **Foreground Service**: Required for background trip recording
- **Foreground Service Location**: Required for location tracking in background
- **Post Notifications**: Required for foreground service notification
- **Wake Lock**: Prevents device from sleeping during trips
- **Boot Completed**: Allows service to restart after device reboot

## How It Works

1. **Start Recording**: Tap "Start Trip Detection" to begin monitoring
2. **Automatic Detection**: The app automatically detects when you start driving
3. **Real-time Monitoring**: As you drive, the app tracks:
   - Speed and speeding events
   - Braking intensity
   - Acceleration patterns
   - Cornering sharpness
   - Phone usage while driving
4. **Trip Completion**: Trips end automatically when you stop moving, or manually via the "End Trip" button
5. **Scoring**: Each trip receives a score from 0-100 (converted to 0-5 stars) based on:
   - Number and severity of events
   - Trip duration and distance
   - Night driving factor
   - Phone distraction time

## Event Detection

### Speeding
- Dynamic thresholds based on current speed
- Minor: +10 km/h over threshold
- Mid: +20 km/h over threshold
- Major: +30 km/h over threshold

### Braking
- Minor: < -1.5 m/s²
- Mid: < -2.5 m/s²
- Major: < -3.5 m/s²

### Acceleration
- Minor: > 3.5 m/s²
- Mid: > 4.5 m/s²
- Major: > 6.0 m/s²

### Cornering
- Minor: > 2.5 m/s² lateral acceleration
- Mid: > 3.5 m/s² lateral acceleration
- Major: > 5.0 m/s² lateral acceleration

### Event Grouping
Successive events of the same type within 10 seconds are counted as a single event, categorized by the worst severity.

## Testing

The app includes a test mode for simulating trips without actual driving:

1. Navigate to the home screen
2. Tap the car icon 5 times consecutively to unlock test buttons
3. Select a test scenario:
   - **Normal Test**: Simulates a typical trip with normal braking
   - **Hard Brake Test**: Simulates a trip with aggressive braking

## Background Behavior

- The app runs as a foreground service when recording is active
- Recording automatically stops when you tap "Stop Recording"
- If a trip is in progress when stopping recording, the trip is automatically ended and saved
- The service does not run in the background when recording is not active

## Data Storage

All trip data is stored locally using Room database:
- **Trips**: Trip metadata, scores, and statistics
- **Routes**: Route fingerprints for trip grouping
- **Location Points**: GPS coordinates along the route
- **Event Markers**: Locations where events occurred

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]

