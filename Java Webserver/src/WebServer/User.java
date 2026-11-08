package WebServer;
import java.util.Base64;
import java.util.Random;

public class User
{
	String username;
	String passwordHash;
	String sessionToken;
	
	public User()
	{
		sessionToken = generateSessionToken();
	}
	
	public User(String token)
	{
		sessionToken = token;
	}
	
	public static String generateSessionToken()
	{
		byte[] buf = new byte[32];
		new Random().nextBytes(buf);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
	}
}
