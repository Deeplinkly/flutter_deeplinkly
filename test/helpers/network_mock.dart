/// Network response mocking for testing
class NetworkMock {
  static Map<String, MockResponse> responses = {};

  /// Set up a mock response for a URL
  static void setResponse(String url, MockResponse response) {
    responses[url] = response;
  }

  /// Get mock response for a URL
  static MockResponse? getResponse(String url) {
    return responses[url];
  }

  /// Clear all mock responses
  static void clear() {
    responses.clear();
  }

  /// Create a successful deep link resolution response
  static MockResponse createSuccessResponse({
    required String clickId,
    Map<String, dynamic>? params,
  }) {
    final responseData = {
      'click_id': clickId,
      'params': params ?? {},
    };
    return MockResponse(
      statusCode: 200,
      body: responseData,
    );
  }

  /// Create a network error response
  static MockResponse createErrorResponse({
    int statusCode = 500,
    String? errorMessage,
  }) {
    return MockResponse(
      statusCode: statusCode,
      body: {'error': errorMessage ?? 'Network error'},
      shouldThrow: true,
    );
  }

  /// Create a timeout response
  static MockResponse createTimeoutResponse() {
    return MockResponse(
      statusCode: 0,
      body: {},
      shouldThrow: true,
      isTimeout: true,
    );
  }
}

/// Mock network response
class MockResponse {
  final int statusCode;
  final Map<String, dynamic> body;
  final bool shouldThrow;
  final bool isTimeout;
  final Duration? delay;

  MockResponse({
    required this.statusCode,
    required this.body,
    this.shouldThrow = false,
    this.isTimeout = false,
    this.delay,
  });

  /// Simulate network delay
  Future<void> simulateDelay() async {
    if (delay != null) {
      await Future.delayed(delay!);
    }
  }
}

/// Sample network responses for testing
class SampleNetworkResponses {
  static Map<String, dynamic> successfulDeepLink({
    String clickId = 'test_click_123',
    String? utmSource,
    String? utmMedium,
    String? utmCampaign,
  }) {
    return {
      'click_id': clickId,
      'params': {
        if (utmSource != null) 'utm_source': utmSource,
        if (utmMedium != null) 'utm_medium': utmMedium,
        if (utmCampaign != null) 'utm_campaign': utmCampaign,
      },
    };
  }

  static Map<String, dynamic> networkError() {
    return {
      'error': 'Network request failed',
      'error_code': 'NETWORK_ERROR',
    };
  }

  static Map<String, dynamic> timeoutError() {
    return {
      'error': 'Request timeout',
      'error_code': 'TIMEOUT',
    };
  }
}

