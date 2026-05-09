package limhjun.me.shortly.url;

public class ShortUrlNotFoundException extends RuntimeException {
    public ShortUrlNotFoundException(String code) {
        super("Short URL not found: " + code);
    }
}
