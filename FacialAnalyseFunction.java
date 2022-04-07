package myfunction.service.impl.handler.client;

import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.*;
import com.amazonaws.util.IOUtils;
import myfunction.config.ConfigStatus;
import myfunction.domaine.dto.request.client.FacialAnalyseCardDto;
import myfunction.domaine.entity.Customer;
import myfunction.domaine.entity.UserStandart;
import myfunction.domaine.repositories.impl.CustomerRepository;
import myfunction.domaine.repositories.impl.UserStandartRepository;
import myfunction.utils.EmailUtil;
import myfunction.utils.MultiPartFileUtil;
import myfunction.utils.S3TethysServiceImpl;
import myfunction.utils.TokenServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

public class FacialAnalyseServiceImpl {

    private final S3TethysServiceImpl s3TethysService;
    private Float confidence;
    private String gender;
    private String ageRange;
    private String fileNameAnalyseFacial;
    private String fileAnalyeFacial;
    private String imageUser;
    private final AmazonRekognition rekognitionClient;
    private MultipartFile multipartImage;
    private final CustomerRepository customerRepository;
    private final UserStandartRepository userStandartRepository;
    private final TokenServiceImpl tokenService;

    private final Logger logger = LoggerFactory.getLogger(FacialAnalyseServiceImpl.class);

    public FacialAnalyseServiceImpl() {
        this.tokenService = new TokenServiceImpl();
        this.customerRepository = new CustomerRepository();
        this.s3TethysService = new S3TethysServiceImpl();
        this.rekognitionClient = AmazonRekognitionClientBuilder.defaultClient();
        this.userStandartRepository = new UserStandartRepository();
    }

    private Boolean verifContrainte(String username, String baseImage, String nameImage) {
        if (username == null || baseImage == null || nameImage == null)
            return false;
        return !username.isEmpty() && !baseImage.isEmpty() && !nameImage.isEmpty();
    }

    /**
     * Cette methode permet de faire l'analyse facial
     *
     * @param multipartFile
     * @param username
     * @return
     */
    private Boolean analyseFacial(final MultipartFile multipartFile, final String username) {

        ByteBuffer sourceImageBytes;

        try {
            sourceImageBytes = ByteBuffer.wrap(IOUtils.toByteArray(multipartFile.getInputStream()));
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        final Image image = new Image()
                .withBytes(sourceImageBytes);

        //call operation
        final DetectFacesRequest requestRekognition = new DetectFacesRequest()
                .withImage(image)
                .withAttributes(Attribute.ALL);

        try {
            final DetectFacesResult result = rekognitionClient.detectFaces(requestRekognition);
            final List<FaceDetail> faceDetails = result.getFaceDetails();

            if (!faceDetails.isEmpty()) {
                if (requestRekognition.getAttributes().contains("ALL")) {
                    try {
                        //recuperer le premier resultat
                        FaceDetail face = faceDetails.get(0);

                        /*recuperation des infos de l'analyse*/
                        this.fileNameAnalyseFacial = username + "**analysefaciale**human**" + UUID.randomUUID().toString() + ".txt";
                        this.fileAnalyeFacial = printFaceDetails(face, username);

                        if (confidence >= 78) {
                            this.imageUser = s3TethysService.uploadUserPictureStandartIntoS3(multipartImage);
                            return fileAnalyeFacial != null;
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

        } catch (AmazonRekognitionException e) {
            e.printStackTrace();
        }

        return false;
    }


    /**
     * Cette methode permet de creer un nouvelle carte pour un utlisateur
     *
     * @param facialAnalyseCardDto
     * @return
     */
    public Object makeAnalyseFacial(final FacialAnalyseCardDto facialAnalyseCardDto) {

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);

        try {
            /*premiere etape: verification des informations de la requetes*/
            logger.info("*premiere etape: verification des informations de la requete");
            if (!verifContrainte(facialAnalyseCardDto.getUsername().trim(), facialAnalyseCardDto.getBaseimage(), facialAnalyseCardDto.getNameimage()))
                return new ResponseEntity<>("Error occurred while checking information", httpHeaders, HttpStatus.BAD_REQUEST);
            /*deuxieme etape: verification de l'username dans la base de données*/
            logger.info("*troisieme etape: verification de l'username dans la base de données*");
            //Verification dans la table des comptes existants
            if (customerRepository.findByUsername(facialAnalyseCardDto.getUsername().trim()) != null)
                return new ResponseEntity<>("This account already exists", httpHeaders, HttpStatus.BAD_REQUEST);
            /*troisieme etape: recuperation de l'user dans la base de données*/
            logger.info("*troisieme etape: recuperation de l'user dans la base de données*");
            UserStandart userStandartRecup = userStandartRepository.findByUsername(facialAnalyseCardDto.getUsername().trim());
            if (userStandartRecup == null)
                return new ResponseEntity<>("This user does not exist", httpHeaders, HttpStatus.BAD_REQUEST);
            /*troisieme etape: verification la base64 dans la base de données*/
            logger.info("*quatrieme etape: verification la base64 dans la base de données*");
            multipartImage = MultiPartFileUtil.verifInfoAndConvertIntoMultipart(facialAnalyseCardDto.getBaseimage(), facialAnalyseCardDto.getNameimage());
            if (multipartImage == null)
                return new ResponseEntity<>("Error occurred while checking the database 64", httpHeaders, HttpStatus.BAD_REQUEST);
            /*quatrieme etape: analyse facial*/
            logger.info("*cinquieme etape: analyse facial*");
            if (!analyseFacial(multipartImage, facialAnalyseCardDto.getUsername().trim()))
                return new ResponseEntity<>("Error occurred during facial scan", httpHeaders, HttpStatus.BAD_REQUEST);
            /*quatrieme etape: verifier la confidence*/
            logger.info("*sixieme etape: verifier la confidence*");
            if (this.confidence < 79)
                return new ResponseEntity<>("The confidence rate is less than 79", httpHeaders, HttpStatus.BAD_REQUEST);
            /*cinquieme etape: enregistrement de l'analyse sur S3*/
            logger.info("*septieme etape: enregistrement de l'analyse sur S3*");
            String urlAnalyseFacial = s3TethysService.uploadInfoAnalyseIntoS3(fileAnalyeFacial, fileNameAnalyseFacial);
            if (urlAnalyseFacial == null)
                return new ResponseEntity<>("Error occurred while saving info on s3", httpHeaders, HttpStatus.BAD_REQUEST);
            /*sixieme etape: Enregistrement des infos de l'user sur dynamo*/
            logger.info("*septieme etape: Enregistrement des infos de l'user sur dynamo*");

            //save new customer
            Customer customer = new Customer();
            customer.setTauxConfidence(this.confidence);
            customer.setPassword(new BCryptPasswordEncoder().encode(userStandartRecup.getPassword()));
            customer.setAnalyseFacialUrl(urlAnalyseFacial);
            customer.setUsername(facialAnalyseCardDto.getUsername().trim());
            customer.setUrlPictureUser(this.imageUser);
            customer.setEmail(userStandartRecup.getEmail());
            customer.setCountry(userStandartRecup.getCountry());
            customer.setRegion(userStandartRecup.getRegion());
            customer.setMobileNo(userStandartRecup.getMobileNo());
            customer.setConsent(userStandartRecup.getConsent());
            customer.setGender(this.gender);
            customer.setStatus(ConfigStatus.IS_PENDING);
            customer.setAgeRange(this.ageRange);
            customer.setOptOut(false);
            customer.setActive(1);
            /*cinquieme etape: Enregistrement des infos dans dynamo*/
            logger.info("*cinquieme etape: Enregistrement des infos dans dynamo*");
            final String token = tokenService.generateTokenAfterLogin(facialAnalyseCardDto.getUsername().trim());
            httpHeaders.set("jwt", token);
            //register data
            customerRepository.save(customer);
            //send welcome email
            EmailUtil.sendRandowEmail("User", facialAnalyseCardDto.getUsername().trim(), userStandartRecup.getPassword(), customer.getEmail());
            //get customer saved
            Customer customerSaved = customerRepository.findByUsername(facialAnalyseCardDto.getUsername().trim());
            //delete userstandart
            userStandartRepository.delete(userStandartRecup);

            return new ResponseEntity<>(customerSaved, httpHeaders, HttpStatus.CREATED);

        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseEntity<>("unexpected error during processing", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    /**
     * Cette methode permet d'imprimer les données de la reconnaissance dans un String
     *
     * @param faceDetail
     * @param username
     * @return
     */
    private String printFaceDetails(final FaceDetail faceDetail, final String username) {

        try {
            String myFile = "************************ RESULTAT DE L'ANALYSE DE " + username + " *************************\r\r\r";

            AgeRange ageRange = faceDetail.getAgeRange();
            myFile += "Age range: " + ageRange.getLow() + "-" + ageRange.getHigh() + "\n";
            this.ageRange = ageRange.getLow() + "-" + ageRange.getHigh();

            Beard beard = faceDetail.getBeard();
            myFile += "Beard: " + beard.getValue() + "; confidence=" + beard.getConfidence() + "\n";

            BoundingBox bb = faceDetail.getBoundingBox();
            myFile += "BoundingBox: left=" + bb.getLeft() +
                    ", top=" + bb.getTop() + ", width=" + bb.getWidth() +
                    ", height=" + bb.getHeight() + "\n";

            Float confidence = faceDetail.getConfidence();
            myFile += "Confidence: " + confidence + "\n";

            /*Affectation de la valeur de la confidence*/
            this.confidence = confidence;

            List<Emotion> emotions = faceDetail.getEmotions();
            for (Emotion emotion : emotions) {
                myFile += "Emotion: " + emotion.getType() +
                        "; confidence=" + emotion.getConfidence() + "\n";
            }

            Eyeglasses eyeglasses = faceDetail.getEyeglasses();
            myFile += "Eyeglasses: " + eyeglasses.getValue() +
                    "; confidence=" + eyeglasses.getConfidence() + "\n";

            EyeOpen eyesOpen = faceDetail.getEyesOpen();
            myFile += "EyeOpen: " + eyesOpen.getValue() +
                    "; confidence=" + eyesOpen.getConfidence() + "\n";

            Gender gender = faceDetail.getGender();
            myFile += "Gender: " + gender.getValue() +
                    "; confidence=" + gender.getConfidence() + "\n";
            this.gender = gender.getValue();

            List<Landmark> landmarks = faceDetail.getLandmarks();
            for (Landmark lm : landmarks) {
                myFile += "Landmark: " + lm.getType()
                        + ", x=" + lm.getX() + "; y=" + lm.getY() + "\n";
            }

            MouthOpen mouthOpen = faceDetail.getMouthOpen();
            myFile += "MouthOpen: " + mouthOpen.getValue() +
                    "; confidence=" + mouthOpen.getConfidence() + "\n";

            Mustache mustache = faceDetail.getMustache();
            myFile += "Mustache: " + mustache.getValue() +
                    "; confidence=" + mustache.getConfidence() + "\n";

            Pose pose = faceDetail.getPose();
            myFile += "Pose: pitch=" + pose.getPitch() +
                    "; roll=" + pose.getRoll() + "; yaw" + pose.getYaw() + "\n";

            ImageQuality quality = faceDetail.getQuality();
            myFile += "Quality: brightness=" +
                    quality.getBrightness() + "; sharpness=" + quality.getSharpness() + "\n";

            Smile smile = faceDetail.getSmile();
            myFile += "Smile: " + smile.getValue() +
                    "; confidence=" + smile.getConfidence() + "\n";

            Sunglasses sunglasses = faceDetail.getSunglasses();
            myFile += "Sunglasses=" + sunglasses.getValue() +
                    "; confidence=" + sunglasses.getConfidence() + "\n";

            return myFile;

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}


