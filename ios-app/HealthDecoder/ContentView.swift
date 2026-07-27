import SwiftUI

struct ContentView: View {
    var body: some View {
        NavigationView {
            VStack {
                Image(systemName: "cross.case.fill")
                    .imageScale(.large)
                    .foregroundColor(.accentColor)
                Text("HealthDecoder iOS")
                    .font(.largeTitle)
                    .padding()
                
                Text("Welcome to the HealthDecoder iOS app.")
            }
            .navigationTitle("Home")
        }
    }
}

#Preview {
    ContentView()
}
