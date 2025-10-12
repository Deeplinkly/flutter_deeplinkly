// DomainConfig.swift
enum DomainConfig {
    static let base = "https://warm-leopard-uniquely.ngrok-free.app"
    static let enrich = "\(base)/api/v1/enrich"
    static let sdkError = "\(base)/api/v1/sdk-error"
    static let resolveClick = "\(base)/api/v1/resolve"
    static let generateLink = "\(base)/api/v1/generate-url"
}
