import Foundation

class APIClient {
    static let shared = APIClient()
    
    // Set this to your deployed API Gateway URL or local ngrok URL
    let baseURL = URL(string: "https://your-api-gateway-url.amazonaws.com/prod")!
    
    func fetchTranslations(completion: @escaping (Result<[String: String], Error>) -> Void) {
        // Implementation for GET /api/translations
    }
    
    // Add other networking endpoints here...
}
