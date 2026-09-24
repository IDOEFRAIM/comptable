package com.example.comptable.stock.web.dto;

public record CreerAvoirFournisseurRequest(
        String motif,
        boolean retourStock
) {
}
