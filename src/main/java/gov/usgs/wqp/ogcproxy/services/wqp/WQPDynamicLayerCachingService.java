package gov.usgs.wqp.ogcproxy.services.wqp;

import static org.springframework.util.StringUtils.isEmpty;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import gov.usgs.wqp.ogcproxy.exceptions.OGCProxyException;
import gov.usgs.wqp.ogcproxy.exceptions.OGCProxyExceptionID;
import gov.usgs.wqp.ogcproxy.geo.JsonObjectResponseHandler;
import gov.usgs.wqp.ogcproxy.model.cache.DynamicLayerCache;
import gov.usgs.wqp.ogcproxy.model.status.DynamicLayerStatus;

public class WQPDynamicLayerCachingService {
	private static final Logger LOG = LoggerFactory.getLogger(WQPDynamicLayerCachingService.class);

	public static final String DATASTORES = "dataStores";
	public static final String DATASTORE = "dataStore";

	@Autowired
	private Environment environment;
	private static boolean initialized;
	
	private static Map<String, DynamicLayerCache> requestToLayerCache;
	
	private static long threadSleep  = 500;
	private static String geoserverProtocol   = "http";
	private static String geoserverHost       = "localhost";
	private static String geoserverPort       = "8080";
	private static String geoserverContext    = "geoserver";
	private static String wqpWorkspace        = "qw_portal_map";
	private static String geoserverBaseUri    = "";
	private static String geoserverDatastores = "datastores.json";
	private static String geoserverRest       = "rest";
	private static String geoserverWorkspaces = "workspaces";
	private static String geoserverUser = "";
	private static String geoserverPass = "";
	/* ====================================================================== */
		
	/*
	 * INSTANCE		===========================================================
	 * ========================================================================
	 */
	/* ====================================================================== */
	private static final WQPDynamicLayerCachingService INSTANCE = new WQPDynamicLayerCachingService();
	/* ====================================================================== */
	
	/**
	 * Private Constructor for Singleton Pattern
	 */
	private WQPDynamicLayerCachingService() {
		requestToLayerCache = new ConcurrentHashMap<String, DynamicLayerCache>();
		initialized = false;
	}
	
	/**
	 * Singleton accessor
	 *
	 * @return WQPDynamicLayerCachingService instance
	 */
	public static WQPDynamicLayerCachingService getInstance() {
		return INSTANCE;
	}
	
	@PostConstruct
	public void initialize() {
		LOG.debug("WQPDynamicLayerCachingService.initialize() called");
		
		/*
		 * Since we are using Spring DI we cannot access the environment bean
		 * in the constructor.  We'll just use a locked initialized variable
		 * to check initialization after instantiation and set the env
		 * properties here.
		 */
		if (initialized) {
			return;
		}
		synchronized(WQPDynamicLayerCachingService.class) {
			if (initialized) {
				return;
			}
			initialized = true;
			
			try {
				threadSleep = Long.parseLong(environment.getProperty("proxy.thread.sleep"));
			} catch (Exception e) {
				LOG.error("WQPDynamicLayerCachingService() Constructor Exception: Failed to parse property [proxy.thread.sleep] " +
						  "- Keeping thread sleep default to [" + threadSleep + "].\n" + e.getMessage() + "\n");
			}
			
			String tmp = environment.getProperty("wqp.geoserver.proto");
			if (!isEmpty(tmp)) {
				geoserverProtocol = tmp;
			}
			tmp = environment.getProperty("wqp.geoserver.host");
			if (!isEmpty(tmp)) {
				geoserverHost = tmp;
			}
			tmp = environment.getProperty("wqp.geoserver.port");
			if (!isEmpty(tmp)) {
				geoserverPort= tmp;
			}
			tmp = environment.getProperty("wqp.geoserver.context");
			if (!isEmpty(tmp)) {
				geoserverContext = tmp;
			}
			tmp = environment.getProperty("wqp.geoserver.user");
			if (!isEmpty(tmp)) {
				geoserverUser = tmp;
			}
			tmp = environment.getProperty("wqp.geoserver.pass");
			if (!isEmpty(tmp)) {
				geoserverPass = tmp;
			}
			tmp = environment.getProperty("wqp.geoserver.workspace");
			if (!isEmpty(tmp)) {
				wqpWorkspace = tmp;
			}
			geoserverBaseUri = geoserverProtocol + "://" + geoserverHost + ":" + geoserverPort + "/" + geoserverContext;
			populateCache();
		}
	}
	
	/**
	 * getLayerCache()
	 * @param defaultLayerCache
	 * @return DynamicLayerCache
	 * <br /><br />
	 * This method will return an existing DynamicLayerCache object if it exists
	 * in its internal data store.  If it does NOT contain an existing Cache
	 * object, it will then create one and return the newly created object.  This
	 * newly created Cache Object's internal state will equal:<br />
	 * 			DynamicLayerStatus.INITIATED
	 * <br /><br />
	 * To keep the DynamicLayerCache object's state from becoming corrupted by
	 * multiple threads requesting the object, this method will BLOCK on
	 * returning a DynamicLayerCache object if its internal state value does NOT
	 * equal:<br />
	 * 			DynamicLayerStatus.AVAILABLE
	 * <br /><br />
	 * @throws OGCProxyException
	 */
	public DynamicLayerCache getLayerCache(DynamicLayerCache defaultLayerCache) throws OGCProxyException {
		String key = defaultLayerCache.getKey();
		DynamicLayerCache currentCache = WQPDynamicLayerCachingService.requestToLayerCache.get(key);
		
		if (currentCache == null) {
			/*
			 * This next block is synchronized so that only one thread can enter
			 * it at a time.  We will then recheck to see if the Cache object is
			 * in the data store.  If it is, we continue on.  If it is NOT we
			 * create one, add it to the data store and return.
			 */
			synchronized (WQPDynamicLayerCachingService.class) {
				currentCache = WQPDynamicLayerCachingService.requestToLayerCache.get(key);
				
				if (currentCache == null) {
					currentCache = defaultLayerCache;
					WQPDynamicLayerCachingService.requestToLayerCache.put(key, currentCache);
					
					String msg = "WQPDynamicLayerCachingService.getLayerCache() INFO : DynamicLayerCache object does not " +
							  "exist for key " + key +  ". Creating new Cache Object and setting status to [" +
							  currentCache.getCurrentStatus().toString() + "]";
					LOG.debug(msg);
					
					return currentCache;
				}
			}
		}
		
		/*
		 * Lets check to see if this cache's status is an error.  If so we need
		 * to throw.
		 */
		if (currentCache.getCurrentStatus() == DynamicLayerStatus.ERROR) {
			String msg = "WQPDynamicLayerCachingService.getLayerCache() INFO : Caught Interrupted Exception waiting for " +
					  "Cache object [" + key + "] status to change.  Its current status is [" +
					  currentCache.getCurrentStatus().toString() +
					  "].  Throwing Exception...";
			LOG.error(msg);
			
			OGCProxyExceptionID id = OGCProxyExceptionID.LAYER_CREATION_FAILED;
			throw new OGCProxyException(id, "WQPDynamicLayerCachingService", "getLayerCache()", msg);
		}
		
		/*
		 * There is a legit Cache object in the internal data store.  Now we need to
		 * check its status to see if its being worked on or if its fully available
		 * to being used.  If it's NOT available, we will sleep until it does
		 * become available.
		 *
		 * The internal DynamicLayerStatus object will have its state managed by
		 * the initial thread that created it.  This thread will set the state to
		 * DynamicLayerStatus.AVAILABLE as soon as it becomes available.
		 *
		 * In the catching of the Interrupted Exception, we will double check that
		 * the status is available.  If its not we LOG an error.
		 */
		while ((currentCache.getCurrentStatus() == DynamicLayerStatus.BUILDING)
			|| (currentCache.getCurrentStatus() == DynamicLayerStatus.INITIATED)) {
			
			try {
				String msg = "WQPDynamicLayerCachingService.getLayerCache() INFO : DynamicLayerCache object exists for key [" +
							 key + "] but its status is [" +
							 currentCache.getCurrentStatus().toString() +
							 "].  Waiting " + WQPDynamicLayerCachingService.threadSleep + "ms";
				LOG.debug(msg);
				
				Thread.sleep(WQPDynamicLayerCachingService.threadSleep);
			} catch (InterruptedException e) {
				if ((currentCache.getCurrentStatus() != DynamicLayerStatus.AVAILABLE) && (currentCache.getCurrentStatus() != DynamicLayerStatus.EMPTY)) {
					String msg = "WQPDynamicLayerCachingService.getLayerCache() INFO : Caught Interrupted Exception waiting for " +
							  "Cache object [" + key + "] status to change.  Its current status is [" +
							  currentCache.getCurrentStatus().toString() +
							  "].  Throwing Exception...";
					LOG.error(msg);
					
					OGCProxyExceptionID id = OGCProxyExceptionID.LAYER_CREATION_FAILED;
					throw new OGCProxyException(id, "WQPDynamicLayerCachingService", "getLayerCache()", msg);
				}
			}
		}
		
		String msg = "WQPDynamicLayerCachingService.getLayerCache() INFO : DynamicLayerCache object " +
				  "exists for key " + key +  ". Returning object with status [" +
				  currentCache.getCurrentStatus().toString() + "]";
		LOG.debug(msg);
		
		return currentCache;
	}
	
	public void removeLayerCache(String searchParamKey) {
		synchronized (requestToLayerCache) {
			DynamicLayerCache currentCache = WQPDynamicLayerCachingService.requestToLayerCache.remove(searchParamKey);
			
			/*
			 * Set the status to ERROR so that anyone currently waiting to use the cache will
			 * see it as invalid
			 */
			if (currentCache != null) {
				String msg = "WQPDynamicLayerCachingService.getLayerCache() INFO : Removed Layer Cache for layer name [" +
							 currentCache.getLayerName() + "] for key [" + searchParamKey + "].  Invalidating " +
							 "cache for any current threads.";
				LOG.debug(msg);
				currentCache.setCurrentStatus(DynamicLayerStatus.ERROR);
			}
		}
	}
	
	public Map<String, DynamicLayerCache> getCacheStore() {
		return WQPDynamicLayerCachingService.requestToLayerCache;
	}
	
	public int clearCache() {
		/* 
		 * First, drop the workspace in geoserver to clear it.
		 */
		clearGeoserverWorkspace();
		/*
		 * Then clear in-memory cache.
		 */
		return clearInMemoryCache();
	}
	
	protected void clearGeoserverWorkspace() {
		String deleteUri = String.join("/", geoserverBaseUri, geoserverRest, geoserverWorkspaces, wqpWorkspace) + "?recurse=true";
		try (CloseableHttpClient httpClient = HttpClients.custom().setDefaultCredentialsProvider(getCredentialsProvider()).build()) {
			HttpDelete httpDelete = new HttpDelete(deleteUri);
			httpClient.execute(httpDelete);
		} catch (Exception e) {
			//TODO - OK to just eat?
			LOG.error("Problems resetting workspace in geoserver: " + e.getLocalizedMessage(), e);
		}
	}
	
	protected int clearInMemoryCache() {
		/*
		 * We need to clear the cache in a thread-safe way.  What we are going to
		 * do is actually utilize the thread-safe methods we have already and
		 * clear each item one at a time.
		 */
		List<String> cacheKeys = new Vector<>(WQPDynamicLayerCachingService.requestToLayerCache.keySet());
		int originalCount = cacheKeys.size();
		
		List<String> uncleared = new Vector<>();
		
		for (String cacheKey : cacheKeys) {
			DynamicLayerCache cache = WQPDynamicLayerCachingService.requestToLayerCache.get(cacheKey);
			DynamicLayerCache threadSafeCache = null;
			
			try {
				/*
				 * We'll go through our getLayerCache() method so that if there
				 * is a layer currently being built we will wait until it is
				 * finished before removing it.
				 */
				threadSafeCache = getLayerCache(cache);
			} catch (OGCProxyException e) {
				LOG.error("Issue getting cache key: " + cacheKey, e);
			}
			
			if (threadSafeCache != null) {
				threadSafeCache.setCurrentStatus(DynamicLayerStatus.ERROR);
				removeLayerCache(threadSafeCache.getKey());
			} else {
				uncleared.add(cacheKey);
			}
		}
		
		int clearedCount = originalCount - uncleared.size();
		
		if (!uncleared.isEmpty()) {
			LOG.error("WQPDynamicLayerCachingService.clearCache() ERROR: Removed cache count [" + clearedCount +
					  "] does not equal total cache count [" + originalCount + "].  Potentially introducing " +
					  "stale layers {" + uncleared + "}");
		}
		
		LOG.debug("WQPDynamicLayerCachingService.clearCache() INFO: Removed cache count [" + clearedCount +
				  "] from cache.");
		
		return clearedCount;
	}
	
	protected void populateCache() {
		/*
		 * Run out to Geoserver for the list of layers it has and add them to our cache.
		 * We should be finished before this service responds to any requests, but we will use the thread safe getLayerCache()
		 * just in case...
		 */
		LOG.debug("WQPDynamicLayerCachingService.populateCache() START");
		try (CloseableHttpClient httpClient = HttpClients.custom().setDefaultCredentialsProvider(getCredentialsProvider()).build()) {
			String uri = String.join("/", geoserverBaseUri, geoserverRest, geoserverWorkspaces, wqpWorkspace, geoserverDatastores);
			JsonObject jsonObject = httpClient.execute(new HttpGet(uri), new JsonObjectResponseHandler());
			Iterator<JsonElement> i = getResponseIterator(jsonObject);
			while (i.hasNext()) {
				JsonObject layer = i.next().getAsJsonObject();
				LOG.debug("WQPDynamicLayerCachingService.populateCache() with [" + layer.get("name").getAsString() + "]");
				DynamicLayerCache cacheIt = new DynamicLayerCache(layer.get("name").getAsString(), wqpWorkspace);
				try {
					getLayerCache(cacheIt);
				} catch (OGCProxyException e) {
					LOG.error("Problems populating cache", e);
				}
			}
		} catch (Exception e) {
			LOG.error("Problems loading cache from geoserver: " + e.getLocalizedMessage(), e);
		}
		LOG.debug("WQPDynamicLayerCachingService.populateCache() FINISH");
	}
	
	protected Iterator<JsonElement> getResponseIterator(JsonObject jsonObject) {
		Iterator<JsonElement> rtn = new JsonArray().iterator();
		//We are expecting {"dataStores": {"dataStore: [{....}, {....}, ...]}}
		if (null != jsonObject 
				&& jsonObject.has(DATASTORES) && jsonObject.get(DATASTORES).isJsonObject()
				&& jsonObject.getAsJsonObject(DATASTORES).has(DATASTORE)
				&& jsonObject.getAsJsonObject(DATASTORES).get(DATASTORE).isJsonArray()) {
			rtn = jsonObject.getAsJsonObject(DATASTORES).getAsJsonArray(DATASTORE).iterator();
		}
		return rtn;
	}

	protected CredentialsProvider getCredentialsProvider() {
    	//TODO refactor to either WQPDynamicLayerCachingService or WQPLayerBuildingService
    	CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(
                new AuthScope(geoserverHost, Integer.parseInt(geoserverPort)),
                new UsernamePasswordCredentials(geoserverUser, geoserverPass));
        return credsProvider;
    	//TODO end
	}

	/**
	 * Really only meant to be used by automated tests - Spring will normally handle it with the @Autowired annotation.
	 * @param environment
	 */
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

}
