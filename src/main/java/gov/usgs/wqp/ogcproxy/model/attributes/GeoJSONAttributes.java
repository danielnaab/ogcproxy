package gov.usgs.wqp.ogcproxy.model.attributes;

public class GeoJSONAttributes {
	
	private GeoJSONAttributes() {
	}
	
	public static final String PROVIDER = "ProviderName";
	public static final String ORG_ID = "OrganizationIdentifier";
	public static final String ORG_NAME = "OrganizationFormalName";
	public static final String NAME = "MonitoringLocationIdentifier";
	public static final String LOC_NAME = "MonitoringLocationName";
	public static final String TYPE = "MonitoringLocationTypeName";
	public static final String SEARCH_TYPE = "ResolvedMonitoringLocationTypeName";
	public static final String HUC8 = "HUCEightDigitCode";
	public static final String POINT = "coordinates";
	public static final String ACTIVITY_COUNT = "activityCount";
	public static final String RESULT_COUNT = "resultCount";

}
