package br.com.cshub.taxify.enumerated;

/** Situação de um pedido de "Sincronizar agora" (CP-RN-15 a CP-RN-18). PENDING e RESERVED são os abertos. */
public enum CapturePriorityStatus {
    PENDING, RESERVED, SERVED, CANCELED, EXPIRED;

    public boolean isOpen() {
        return this == PENDING || this == RESERVED;
    }
}
