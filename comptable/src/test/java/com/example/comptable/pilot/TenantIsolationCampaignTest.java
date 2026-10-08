package com.example.comptable.pilot;

import com.example.comptable.collecte.CollecteTestBase;
import com.example.comptable.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Campagne d'isolation entre cabinets, SANS liste écrite à la main : toutes les routes {@code /api/entreprises/{id}/…} déclarées par l'application (GET, POST, PUT, PATCH, DELETE) sont appelées avec le jeton
 * d'un AUTRE cabinet sur l'entreprise du premier. Aucune ne doit répondre 2xx ni 5xx ; les lectures (GET) doivent répondre 403 ou 404 (« introuvable », sans révéler l'existence). Une route ajoutée demain est
 * couverte automatiquement. Un témoin vérifie que la campagne a du sens : le propriétaire, lui, obtient bien des réponses 2xx sur de nombreuses routes.
 */
class TenantIsolationCampaignTest extends CollecteTestBase {

    @Autowired WebApplicationContext contexte;
    @Autowired JwtService jwtService;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;

    private MockMvc mvc;

    @BeforeEach
    void preparer() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
    }

    private record Route(HttpMethod methode, String modele) {
    }

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}/:]+)(?::[^}]*)?}");

    private List<Route> routesParEntreprise() {
        TreeSet<String> vues = new TreeSet<>();
        List<Route> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            if (!e.getValue().getBeanType().isAnnotationPresent(RestController.class) && !e.getValue().getBeanType().getName().contains("web")) {
                continue;
            }
            var chemins = e.getKey().getPathPatternsCondition();
            if (chemins == null) {
                continue;
            }
            for (String chemin : chemins.getPatternValues()) {
                if (!chemin.startsWith("/api/entreprises/{")) {
                    continue;
                }
                var methodes = e.getKey().getMethodsCondition().getMethods();
                for (var m : methodes.isEmpty() ? List.of(org.springframework.web.bind.annotation.RequestMethod.GET) : List.copyOf(methodes)) {
                    if (vues.add(m + " " + chemin)) {
                        routes.add(new Route(HttpMethod.valueOf(m.name()), chemin));
                    }
                }
            }
        }
        return routes;
    }

    /** Remplace la première variable (l'entreprise) par l'identifiant donné et toutes les autres par un identifiant quelconque. */
    private static String instancier(String modele, UUID entreprise) {
        Matcher m = VARIABLE.matcher(modele);
        StringBuilder sb = new StringBuilder();
        boolean premiere = true;
        while (m.find()) {
            m.appendReplacement(sb, premiere ? entreprise.toString() : UUID.randomUUID().toString());
            premiere = false;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private int appeler(Route r, UUID entreprise, String jeton) throws Exception {
        var requete = request(r.methode(), instancier(r.modele(), entreprise)).contentType(MediaType.APPLICATION_JSON).content("{}").header("Authorization", "Bearer " + jeton);
        return mvc.perform(requete).andReturn().getResponse().getStatus();
    }

    @Test
    void uneEntrepriseDUnAutreCabinet_n_estJamaisAccessible_surAucuneRouteDeLApplication() throws Exception {
        Dossier a = nouveauDossier(false);
        Dossier b = nouveauDossier(false);
        String jetonA = jwtService.generateToken(a.expert());
        String jetonB = jwtService.generateToken(b.expert());
        List<Route> routes = routesParEntreprise();
        assertThat(routes.size()).as("routes par entreprise découvertes").isGreaterThan(100);

        List<String> fuites = new ArrayList<>();
        int lecturesProprietaireReussies = 0;
        int lecturesEtrangeres = 0;
        for (Route r : routes) {
            int etranger = appeler(r, a.entreprise().id(), jetonB);
            boolean lecture = r.methode() == HttpMethod.GET;
            if (lecture) {
                lecturesEtrangeres++;
            }
            if (etranger / 100 == 2 || etranger / 100 == 5 || (lecture && etranger != 403 && etranger != 404 && etranger != 400)) {
                fuites.add(r.methode() + " " + r.modele() + " -> " + etranger);
            }
            if (lecture && appeler(r, a.entreprise().id(), jetonA) / 100 == 2) {
                lecturesProprietaireReussies++;
            }
        }
        assertThat(fuites).as("routes qui répondent autrement que 403/404 à un autre cabinet").isEmpty();
        assertThat(lecturesEtrangeres).isGreaterThan(50);
        assertThat(lecturesProprietaireReussies).as("témoin : le propriétaire lit bien ses propres données").isGreaterThan(20);
    }
}
