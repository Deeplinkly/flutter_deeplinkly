class DeeplinklyContent {
  final String canonicalIdentifier;
  final String? title;
  final String? description;
  final String? imageUrl;
  final Map<String, dynamic> metadata;

  const DeeplinklyContent({
    required this.canonicalIdentifier,
    this.title,
    this.description,
    this.imageUrl,
    this.metadata = const {},
  });

  Map<String, dynamic> toJson() => {
        'canonical_identifier': canonicalIdentifier,
        if (title != null) 'title': title,
        if (description != null) 'description': description,
        if (imageUrl != null) 'image_url': imageUrl,
        'metadata': metadata,
      };
}

class DeeplinklyLinkOptions {
  final String channel;
  final String feature;
  final Map<String, dynamic>? tags;

  const DeeplinklyLinkOptions({
    required this.channel,
    required this.feature,
    this.tags,
  });

  Map<String, dynamic> toJson() => {
        'channel': channel,
        'feature': feature,
        if (tags != null) 'tags': tags,
      };
}

class DeeplinklyResult {
  final bool success;
  final String? url;
  final String? errorCode;
  final String? errorMessage;

  const DeeplinklyResult({
    required this.success,
    this.url,
    this.errorCode,
    this.errorMessage,
  });

  factory DeeplinklyResult.fromMap(Map<dynamic, dynamic> map) {
    return DeeplinklyResult(
      success: map['success'] == true,
      url: map['url']['url'] as String?,
      errorCode: map['error_code'] as String?,
      errorMessage: map['error_message'] as String?,
    );
  }
}
