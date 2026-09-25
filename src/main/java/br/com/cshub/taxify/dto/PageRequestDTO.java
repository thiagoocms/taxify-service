package br.com.cshub.taxify.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pagination sent in the request body (for POST search endpoints), as opposed to the
 * page/size/sort query params Spring binds straight into a {@link Pageable}. Every field
 * is optional - whatever is missing falls back to the defaults passed to
 * {@link #toPageable}.
 */
public record PageRequestDTO(
        @Min(0)
        Integer page,
        @Min(1)
        @Max(200)
        Integer size,
        String sort,
        Sort.Direction direction
) {

    public static Pageable toPageable(PageRequestDTO request, String defaultSort, Sort.Direction defaultDirection) {
        int page = request != null && request.page() != null ? request.page() : 0;
        int size = request != null && request.size() != null ? request.size() : 20;
        String sort = request != null && request.sort() != null && !request.sort().isBlank() ? request.sort() : defaultSort;
        Sort.Direction direction = request != null && request.direction() != null ? request.direction() : defaultDirection;
        return PageRequest.of(page, size, Sort.by(direction, sort));
    }
}
