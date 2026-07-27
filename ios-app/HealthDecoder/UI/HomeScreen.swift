import SwiftUI

struct HomeScreen: View {
    let tiles = [
        ("Scan Report", "doc.viewfinder"),
        ("Records", "folder.fill"),
        ("Reminders", "bell.fill"),
        ("Medications", "pills.fill"),
        ("Pending Tests", "testtube.2"),
        ("Trends", "chart.xyaxis.line")
    ]
    
    var body: some View {
        NavigationView {
            ScrollView {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 20) {
                    ForEach(tiles, id: \.0) { tile in
                        NavigationLink(destination: Text("\(tile.0) Screen Placeholder")) {
                            VStack {
                                Image(systemName: tile.1)
                                    .font(.system(size: 40))
                                    .foregroundColor(.accentColor)
                                    .padding(.bottom, 8)
                                Text(tile.0)
                                    .font(.headline)
                                    .foregroundColor(.primary)
                            }
                            .frame(maxWidth: .infinity, minHeight: 120)
                            .background(Color(.secondarySystemBackground))
                            .cornerRadius(12)
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("Home")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    NavigationLink(destination: Text("Account Placeholder")) {
                        Image(systemName: "person.circle.fill")
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: {
                        // Language switcher
                    }) {
                        Image(systemName: "globe")
                    }
                }
            }
        }
    }
}
