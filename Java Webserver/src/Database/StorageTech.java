package Database;
import WebServer.User;

public interface StorageTech
{
	public void storeUser(String storeQuery, User user);
	public User getUser(String searchQuery);
}
