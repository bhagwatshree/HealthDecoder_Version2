import SwiftUI

struct ChatScreen: View {
    var context: String
    @State private var messageText = ""
    
    var body: some View {
        VStack {
            Text("Asking about: \(context)")
                .font(.caption)
                .foregroundColor(.secondary)
                .padding()
            
            Spacer()
            
            HStack {
                TextField("Ask anything...", text: $messageText)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                Button(action: {
                    // Send message to Gemini/Backend
                }) {
                    Image(systemName: "paperplane.fill")
                }
            }
            .padding()
        }
        .navigationTitle("AI Assistant")
    }
}
