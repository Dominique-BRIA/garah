package com.garah.api.commun;

import com.garah.api.commun.stockage.DepotFichiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La détection du type réel d'un fichier téléversé.
 *
 * <p>🎯 <b>Ce que ce test protège.</b> L'en-tête {@code Content-Type} est
 * fourni par le client et se falsifie en une ligne de code. Un fichier HTML
 * annoncé {@code image/png} passerait la liste blanche, serait stocké avec une
 * extension {@code .png}… mais resterait du HTML. Servi depuis le domaine des
 * médias, il exécuterait du script dans le navigateur des visiteurs.</p>
 *
 * <p>On relit donc les premiers octets — le « nombre magique ». Ce n'est pas
 * infaillible (on peut forger un fichier valide pour deux formats), mais cela
 * ferme le cas courant, et c'est ce qui compte.</p>
 *
 * <p>Aucune base, aucun réseau : c'est du calcul sur des octets.</p>
 */
@DisplayName("Détection du type réel d'un fichier")
class DepotFichiersTest {

    @Test
    @DisplayName("reconnaît un JPEG à sa signature")
    void reconnaitJpeg() {
        byte[] jpeg = complete(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        assertThat(DepotFichiers.typeReel(jpeg)).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("reconnaît un PNG à sa signature")
    void reconnaitPng() {
        byte[] png = complete(new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        assertThat(DepotFichiers.typeReel(png)).isEqualTo("image/png");
    }

    @Test
    @DisplayName("reconnaît un PDF")
    void reconnaitPdf() {
        byte[] pdf = complete("%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(DepotFichiers.typeReel(pdf)).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("reconnaît un WebP dans son conteneur RIFF")
    void reconnaitWebp() {
        byte[] webp = new byte[16];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, webp, 0, 4);
        System.arraycopy("WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, webp, 8, 4);
        assertThat(DepotFichiers.typeReel(webp)).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("reconnaît un MP4 à son atome ftyp")
    void reconnaitMp4() {
        byte[] mp4 = new byte[16];
        System.arraycopy("ftypisom".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0, mp4, 4, 8);
        assertThat(DepotFichiers.typeReel(mp4)).isEqualTo("video/mp4");
    }

    /**
     * <b>Le test qui compte vraiment.</b>
     *
     * <p>Du HTML se présentant comme une image doit être refusé — c'est
     * exactement la situation qu'un attaquant fabrique.</p>
     */
    @Test
    @DisplayName("refuse du HTML déguisé en image")
    void refuseDuHtmlDeguise() {
        byte[] html = complete(
                "<html><script>".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        assertThat(DepotFichiers.typeReel(html)).isNull();
    }

    @Test
    @DisplayName("refuse un fichier trop court pour être identifié")
    void refuseUnFichierTropCourt() {
        // Un fichier de 3 octets ne peut porter aucune signature connue.
        // Renvoyer null plutôt que de deviner est le bon comportement.
        assertThat(DepotFichiers.typeReel(new byte[]{1, 2, 3})).isNull();
        assertThat(DepotFichiers.typeReel(null)).isNull();
    }

    /**
     * Les octets lus pour l'inspection doivent pouvoir être remis en tête du
     * flux — sinon le fichier déposé serait amputé de son en-tête, donc
     * corrompu, et <b>silencieusement</b>.
     */
    @Test
    @DisplayName("la lecture d'en-tête ne perd aucun octet")
    void laLectureNePerdRien() throws IOException {
        byte[] contenu = "0123456789ABCDEFGHIJ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var flux = new ByteArrayInputStream(contenu);

        byte[] debut = DepotFichiers.premiersOctets(flux, 16);

        assertThat(debut).hasSize(16);
        assertThat(flux.readAllBytes()).hasSize(contenu.length - 16);
    }

    @Test
    @DisplayName("un fichier plus court que l'en-tête demandé est lu en entier")
    void litUnFichierPlusCourtQueLEntete() throws IOException {
        byte[] contenu = {1, 2, 3, 4};
        byte[] debut = DepotFichiers.premiersOctets(new ByteArrayInputStream(contenu), 16);

        // Et pas un tableau de 16 octets dont 12 zéros : ces zéros seraient
        // interprétés comme faisant partie du fichier.
        assertThat(debut).hasSize(4);
    }

    /** Complète jusqu'à 16 octets, longueur minimale pour l'inspection. */
    private static byte[] complete(byte[] debut) {
        byte[] plein = new byte[16];
        System.arraycopy(debut, 0, plein, 0, Math.min(debut.length, 16));
        return plein;
    }
}
