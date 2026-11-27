package br.com.cshub.taxify.common;

import br.com.cshub.taxify.exception.BadRequestException;

import java.util.UUID;

/** Ids that arrive as text (query params, filters): an invalid or missing one is a 400, not a 500. */
public final class RequestIds {

    private RequestIds() {
    }

    public static UUID parse(String value, MessageService messageService) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }
    }
}
