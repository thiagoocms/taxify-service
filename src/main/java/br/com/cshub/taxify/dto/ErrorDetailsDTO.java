package br.com.cshub.taxify.dto;

public record ErrorDetailsDTO(Long timestamp, Integer status, String message, String details) {
}
