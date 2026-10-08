package com.example.comptable.document.domain;

/** Canal d'entree de la piece. {@code WEB} = depot depuis l'application (upload) ; {@code API}, {@code SCAN} et {@code MANUAL}
 * sont reconnus par le modele pour que le canal reste tracable meme avant leur implementation. */
public enum CanalSource {
    WEB,
    WHATSAPP,
    EMAIL,
    API,
    SCAN,
    MANUAL
}
