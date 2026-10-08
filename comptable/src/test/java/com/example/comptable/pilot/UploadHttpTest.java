package com.example.comptable.pilot;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.collecte.service.CollecteService;
import com.example.comptable.collecte.service.DepotLienService;
import com.example.comptable.demande.repository.DemandePieceRepository;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Dépôt de fichiers par le VRAI chemin HTTP (multipart/form-data), authentifié ET par lien public : un exécutable présenté comme PNG est refusé proprement (4xx métier) sans AUCUNE trace —
 * ni document en base, ni fichier conservé. Les deux chemins partagent le même contrôle de contenu ({@code DocumentService.validerSignature}). Ce n'est pas un antivirus : il vérifie que le
 * contenu est bien du PDF, du JPEG ou du PNG, la taille, et neutralise le nom de fichier.
 */
class UploadHttpTest extends CollecteTestBase {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
            0x1F, 0x15, (byte) 0xC4, (byte) 0x89, 0, 0, 0, 0x0A, 'I', 'D', 'A', 'T', 0x78, (byte) 0x9C, 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
            0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0, (byte) 0xFF, (byte) 0xD9};
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] EXE = "MZ\u0090\u0000\u0003\u0000\u0000\u0000 This program cannot be run in DOS mode.".getBytes(StandardCharsets.ISO_8859_1);

    @Autowired WebApplicationContext contexte;
    @Autowired JwtService jwtService;
    @Autowired CollecteService collecteService;
    @Autowired DepotLienService depotLienService;
    @Autowired DemandePieceRepository demandeRepository;
    @Autowired com.example.comptable.security.RateLimiter rateLimiter;
    @Value("${storage.documents.root-dir}") String racineStockage;

    private MockMvc mvc;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        rateLimiter.reinitialiser("depot-envoi|127.0.0.1");
        rateLimiter.reinitialiser("depot-lecture|127.0.0.1");
    }

    private long documents(Dossier d) {
        return jdbc.queryForObject("select count(*) from document where entreprise_id = ?", Long.class, d.id());
    }

    private long fichiersStockes(Dossier d) throws Exception {
        UUID tenant = entrepriseRepository.findById(d.id()).orElseThrow().getTenant().getId();
        Path dossier = Path.of(racineStockage).toAbsolutePath().normalize().resolve(tenant.toString()).resolve(d.id().toString());
        if (!Files.isDirectory(dossier)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dossier)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    private MockHttpServletResponse authentifie(Dossier d, MockMultipartFile fichier) throws Exception {
        return mvc.perform(multipart("/api/entreprises/" + d.id() + "/documents").file(fichier).param("typeDocument", "RECU")
                .header("Authorization", "Bearer " + jwtService.generateToken(d.expert()))).andReturn().getResponse();
    }

    private MockHttpServletResponse parLien(Dossier d, MockMultipartFile fichier) throws Exception {
        acheterSansPiece(d, 4200);
        UUID finding = piecesManquantes(d).get(0).getId();
        UUID demandeId = collecteService.demander(d.id(), List.of(finding), d.expert()).demandeIds().get(0);
        return mvc.perform(multipart("/api/depot/" + depotLienService.jeton(demandeId)).file(fichier)).andReturn().getResponse();
    }

    private static MockMultipartFile fichier(String nom, String type, byte[] contenu) {
        return new MockMultipartFile("fichier", nom, type, contenu);
    }

    @Test
    void fauxPng_cheminAuthentifie_refuse415_sansDocumentNiFichier() throws Exception {
        Dossier d = nouveauDossier(false);
        long docs = documents(d);
        long fichiers = fichiersStockes(d);
        MockHttpServletResponse r = authentifie(d, fichier("photo.png", "image/png", EXE));
        assertThat(r.getStatus()).isEqualTo(415);
        assertThat(r.getContentAsString()).contains("UNSUPPORTED_MEDIA_TYPE").doesNotContain("Exception").doesNotContain("at com.");
        assertThat(documents(d)).as("aucun document persisté").isEqualTo(docs);
        assertThat(fichiersStockes(d)).as("aucun fichier conservé").isEqualTo(fichiers);
    }

    @Test
    void fauxPng_lienPublic_refuse_sansDocumentNiFichier() throws Exception {
        Dossier d = nouveauDossier(false);
        long docs = documents(d);
        long fichiers = fichiersStockes(d);
        MockHttpServletResponse r = parLien(d, fichier("photo.png", "image/png", EXE));
        assertThat(r.getStatus()).isBetween(400, 499);
        assertThat(documents(d)).isEqualTo(docs);
        assertThat(fichiersStockes(d)).isEqualTo(fichiers);
    }

    @Test
    void contenuArbitraireSousUneExtensionPdf_etDoubleExtension_refuses() throws Exception {
        Dossier d = nouveauDossier(false);
        long docs = documents(d);
        assertThat(authentifie(d, fichier("facture.pdf", "application/pdf", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8))).getStatus()).isEqualTo(415);
        assertThat(authentifie(d, fichier("facture.png.exe", "image/png", EXE)).getStatus()).isEqualTo(415);
        assertThat(authentifie(d, fichier("facture.pdf.png", "application/pdf", PNG)).getStatus()).as("PNG annoncé PDF").isEqualTo(415);
        assertThat(authentifie(d, fichier("script.svg", "image/svg+xml", "<svg onload=alert(1)/>".getBytes(StandardCharsets.UTF_8))).getStatus()).isEqualTo(415);
        assertThat(documents(d)).isEqualTo(docs);
    }

    @Test
    void fichierVideEtFichierTropGros_refuses() throws Exception {
        Dossier d = nouveauDossier(false);
        long docs = documents(d);
        assertThat(authentifie(d, fichier("vide.png", "image/png", new byte[0])).getStatus()).isEqualTo(400);
        byte[] gros = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, gros, 0, PNG.length);
        assertThat(authentifie(d, fichier("gros.png", "image/png", gros)).getStatus()).isEqualTo(413);
        assertThat(documents(d)).isEqualTo(docs);
    }

    @Test
    void vraisFichiersAcceptes_etNomHostileNeutralise() throws Exception {
        Dossier d = nouveauDossier(false);
        long docs = documents(d);
        assertThat(authentifie(d, fichier("recu.png", "image/png", PNG)).getStatus()).isBetween(200, 299);
        assertThat(authentifie(d, fichier("recu.jpg", "image/jpeg", JPEG)).getStatus()).isBetween(200, 299);
        assertThat(authentifie(d, fichier("recu.pdf", "application/pdf", PDF)).getStatus()).isBetween(200, 299);
        assertThat(authentifie(d, fichier("../../../../evasion.png", "image/png", PNG)).getStatus()).isBetween(200, 299);
        assertThat(documents(d)).isEqualTo(docs + 4);
        List<String> chemins = jdbc.queryForList("select chemin_fichier from document where entreprise_id = ?", String.class, d.id());
        assertThat(chemins).allSatisfy(c -> assertThat(c).doesNotContain("..").doesNotContain("\\").matches("[0-9a-f-]{36}/[0-9a-f-]{36}/[0-9a-f-]{36}-[A-Za-z0-9._-]+"));
        assertThat(Files.exists(Path.of(racineStockage).toAbsolutePath().normalize().getParent().resolve("evasion.png"))).isFalse();
    }
}
