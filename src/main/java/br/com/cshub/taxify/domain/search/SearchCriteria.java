package br.com.cshub.taxify.domain.search;

import java.util.ArrayList;
import java.util.List;

public class SearchCriteria {

	public static final String ORDER_ASC = "ASC";
	public static final String ORDER_DESC = "DESC";
	
	public static final String ORDER_BY_FIELDS_KEY = "orderByFields";
	public static final String ORDER_BY_KEY = "order";
	public static final String GROUP_BY_KEY = "groupBy";
	
	private String key;
    private String operation;
    private Object value;
    private String order;
    private List<String> orderByFields = new ArrayList<String>();
    private List<String> groupBy = new ArrayList<String>();

	public SearchCriteria(String key, String operation, Object value) {
		super();
		this.key = key;
		this.operation = operation;
		this.value = value;
		this.order = (key.equals(ORDER_BY_KEY) ? value.toString() : null);
	}
	
	public SearchCriteria(String key, String operation, List<String> values) {		
		super();		
		this.key = key;
		this.operation = operation;
		this.value = values;
		this.order = (key.equals(ORDER_BY_KEY) ? value.toString() : null);
	}
	
	public SearchCriteria(String order) {
		super();
		this.key = ORDER_BY_KEY;
		this.order = order;
	}
	
	public SearchCriteria(List<String> values, String key) {
		super();
		switch (key) {
		case GROUP_BY_KEY:
			this.key = GROUP_BY_KEY;
			this.groupBy = values;
			break;
		case ORDER_BY_FIELDS_KEY:
			this.key = ORDER_BY_FIELDS_KEY;
			this.orderByFields = values;
			break;
		default:
			break;
		}
	}

	public String getKey() {
		return key;
	}
	public void setKey(String key) {
		this.key = key;
	}
	public String getOperation() {
		return operation;
	}
	public void setOperation(String operation) {
		this.operation = operation;
	}
	public Object getValue() {
		return value;
	}
	public void setValue(Object value) {
		this.value = value;
	}
	public String getOrder() {
		return order;
	}
	public void setOrder(String order) {
		this.order = order;
	}
	public List<String> getOrderByFields() {
		return orderByFields;
	}
	public void setOrderByFields(List<String> orderByFields) {
		this.orderByFields = orderByFields;
	}
	public List<String> getGroupBy() {
		return groupBy;
	}
	public void setGroupBy(List<String> groupBy) {
		this.groupBy = groupBy;
	}

}
