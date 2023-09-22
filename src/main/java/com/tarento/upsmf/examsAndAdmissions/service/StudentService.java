package com.tarento.upsmf.examsAndAdmissions.service;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.*;
import com.tarento.upsmf.examsAndAdmissions.enums.ApprovalStatus;
import com.tarento.upsmf.examsAndAdmissions.enums.VerificationStatus;
import com.tarento.upsmf.examsAndAdmissions.model.*;
import com.tarento.upsmf.examsAndAdmissions.model.dto.StudentDto;
import com.tarento.upsmf.examsAndAdmissions.repository.CourseRepository;
import com.tarento.upsmf.examsAndAdmissions.repository.InstituteRepository;
import com.tarento.upsmf.examsAndAdmissions.repository.StudentRepository;
import com.tarento.upsmf.examsAndAdmissions.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

@Service
@PropertySource("classpath:application.properties")
@Slf4j
public class StudentService {
    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final InstituteRepository instituteRepository;
    private final ModelMapper modelMapper;
    @Autowired
    private EntityManager entityManager;

    @Value("${gcp.config.file.path}")
    private String gcpConfigFilePath;

    @Value("${gcp.bucket.name}")
    private String gcpBucketName;

    @Value("${gcp.bucket.folder.name}")
    private String gcpFolderName;

    @Value("${gcp.max.file.size}")
    private String gcpMaxFileSize;

    @Value("${gcp.project.id}")
    private String gcpProjectId;

    @Value("${gcp.client.id}")
    private String gcpClientId;

    @Value("${gcp.client.email}")
    private String gcpClientEmail;

    @Value("${gcp.pkcs.key}")
    private String gcpPkcsKey;

    @Value("${gcp.private.key.id}")
    private String gcpPrivateKeyId;

    @Value("${gcp.sub.folder.path}")
    private String subFolderPath;

    @Value("${file.storage.path}")
    private String storagePath;

    @Autowired
    public StudentService(StudentRepository studentRepository, CourseRepository courseRepository, InstituteRepository instituteRepository) {
        this.studentRepository = studentRepository;
        this.modelMapper = new ModelMapper();
        this.courseRepository = courseRepository;
        this.instituteRepository = instituteRepository;
        configureModelMapper();
    }

    private void configureModelMapper() {
        modelMapper.typeMap(StudentDto.class, Student.class).addMappings(mapper -> {
            mapper.skip(Student::setId);
        });
    }

    private String storeFile(MultipartFile file) throws IOException {
        Path filePath = null;
        String fileLocation = null;
        try {
            // validate file
            String fileName = file.getOriginalFilename();
            filePath = Files.createTempFile(fileName.split("\\.")[0], fileName.split("\\.")[1]);
            file.transferTo(filePath);
            validateFile(filePath);
            // create credentials
            ServiceAccountCredentials credentials = ServiceAccountCredentials.fromPkcs8(gcpClientId, gcpClientEmail,
                    gcpPkcsKey, gcpPrivateKeyId, new ArrayList<String>());
            log.info("credentials created");
            Storage storage = StorageOptions.newBuilder().setProjectId(gcpProjectId).setCredentials(credentials).build().getService();
            log.info("storage object created");
            String gcpFileName = gcpFolderName + "/" + Calendar.getInstance().getTimeInMillis() + "_" + fileName;
            BlobId blobId = BlobId.of(gcpBucketName, gcpFileName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
            Blob blob = storage.create(blobInfo, new FileInputStream(filePath.toFile()));
            fileLocation = blob.getMediaLink();
        } catch (IOException e) {
            log.error("Error while uploading attachment", e);
        } finally {
            if (filePath != null) {
                try {
                    Files.delete(filePath);
                } catch (IOException e) {
                    log.error("Unable to delete temp file", e);
                }
            }
        }
        return fileLocation;
        /*String filename = UUID.randomUUID().toString() + "_" + System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path path = Paths.get(storagePath, filename);
        Files.copy(file.getInputStream(), path);
        return path.toString();*/
    }

    @Transactional
    public Student enrollStudent(StudentDto studentDto) throws IOException {
        Student student = modelMapper.map(studentDto, Student.class);

        // Fetching the institute and setting it to the student
        Institute institute = instituteRepository.findByInstituteCode(studentDto.getInstituteCode()); // Assuming findByCode method exists, adjust accordingly
        if (institute == null) {
            throw new RuntimeException("Institute with code " + studentDto.getInstituteCode() + " not found in the database");
        }
        student.setInstitute(institute);

        Course dbCourse = courseRepository.findByCourseCode(studentDto.getCourseCode());
        if (dbCourse == null) {
            throw new RuntimeException("Course with code " + studentDto.getCourseCode() + " not found in the database");
        }

        student.setCourse(dbCourse);
/*
            if (dbCourse.getAvailableSeats() == null) {
                throw new RuntimeException("Seat information not set for course: " + dbCourse.getCourseName());
            }

            if (dbCourse.getAvailableSeats() <= 0) {
                throw new RuntimeException("No seats available for course: " + dbCourse.getCourseName());
            }

            dbCourse.setAvailableSeats(dbCourse.getAvailableSeats() - 1);
            courseRepository.save(dbCourse);*/

        // Generate provisional enrollment number
        String provisionalNumber = generateProvisionalNumber(student);
        student.setProvisionalEnrollmentNumber(provisionalNumber);

        // Set initial verification status to PENDING

        student.setHighSchoolMarksheetPath(storeFile(studentDto.getHighSchoolMarksheet()));
        student.setHighSchoolCertificatePath(storeFile(studentDto.getHighSchoolCertificate()));
        student.setIntermediateMarksheetPath(storeFile(studentDto.getIntermediateMarksheet()));
        student.setIntermediateCertificatePath(storeFile(studentDto.getIntermediateCertificate()));
        student.setVerificationStatus(VerificationStatus.PENDING);
        student.setVerificationDate(LocalDate.now());

        return studentRepository.save(student);
    }

    private String generateProvisionalNumber(Student student) {
        return student.getCourse().getCourseCode() + "-" + UUID.randomUUID().toString();
    }

    public List<Student> getFilteredStudents(Long instituteId, Long courseId, String academicYear, VerificationStatus verificationStatus) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Student> criteriaQuery = criteriaBuilder.createQuery(Student.class);
        Root<Student> studentRoot = criteriaQuery.from(Student.class);

        List<Predicate> predicates = new ArrayList<>();

        if (instituteId != null) {
            predicates.add(criteriaBuilder.equal(studentRoot.get("institute").get("id"), instituteId));
        }
        if (courseId != null) {
            predicates.add(criteriaBuilder.equal(studentRoot.get("course").get("id"), courseId));
        }
        if (academicYear != null && !academicYear.trim().isEmpty()) {
            predicates.add(criteriaBuilder.equal(studentRoot.get("academicYear"), academicYear));
        } else if (academicYear != null) {
            predicates.add(criteriaBuilder.isNull(studentRoot.get("academicYear")));
        }
        if (verificationStatus != null) {
            predicates.add(criteriaBuilder.equal(studentRoot.get("verificationStatus"), verificationStatus));
        }

        criteriaQuery.where(predicates.toArray(new Predicate[0]));

        return entityManager.createQuery(criteriaQuery).getResultList();
    }

    public Optional<Student> getStudentById(Long id) {
        return studentRepository.findById(id);
    }

    public Student updateStudent(Long id, StudentDto studentDto) throws IOException {
        Student existingStudent = studentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Student not found for ID: " + id));

        if (studentDto.getHighSchoolMarksheet() != null) {
            deleteStudentDocument(existingStudent.getHighSchoolMarksheetPath());
            existingStudent.setHighSchoolMarksheetPath(storeFile(studentDto.getHighSchoolMarksheet()));
        }

        if (studentDto.getHighSchoolCertificate() != null) {
            deleteStudentDocument(existingStudent.getHighSchoolCertificatePath());
            existingStudent.setHighSchoolCertificatePath(storeFile(studentDto.getHighSchoolCertificate()));
        }

        if (studentDto.getIntermediateMarksheet() != null) {
            deleteStudentDocument(existingStudent.getIntermediateMarksheetPath());
            existingStudent.setIntermediateMarksheetPath(storeFile(studentDto.getIntermediateMarksheet()));
        }

        if (studentDto.getIntermediateCertificate() != null) {
            deleteStudentDocument(existingStudent.getIntermediateCertificatePath());
            existingStudent.setIntermediateCertificatePath(storeFile(studentDto.getIntermediateCertificate()));
        }

        modelMapper.map(studentDto, existingStudent);
        existingStudent.setVerificationDate(LocalDate.now());
        existingStudent.setVerificationStatus(VerificationStatus.PENDING);
        return studentRepository.save(existingStudent);
    }

    public List<Student> updateStudentStatusToClosed() {
        LocalDate cutoffDate = LocalDate.now().minusDays(14);
        List<Student> rejectedStudents = studentRepository.findByVerificationDateBeforeAndVerificationStatus(cutoffDate, VerificationStatus.REJECTED);

        log.info("Rejected students found to potentially close: " + rejectedStudents.size());

        List<Student> studentsToUpdate = new ArrayList<>();

        for (Student student : rejectedStudents) {
            student.setVerificationStatus(VerificationStatus.CLOSED);
            studentsToUpdate.add(student);
        }

        return studentRepository.saveAll(studentsToUpdate);
    }

    public List<Student> getStudentsPendingForMoreThan21Days(Long courseId, String academicYear) {
        LocalDate twentyOneDaysAgo = LocalDate.now().minusDays(21);
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Student> criteriaQuery = criteriaBuilder.createQuery(Student.class);
        Root<Student> studentRoot = criteriaQuery.from(Student.class);

        List<Predicate> predicates = new ArrayList<>();

        // Adding the condition for students pending for more than 21 days
        predicates.add(criteriaBuilder.lessThanOrEqualTo(studentRoot.get("enrollmentDate"), twentyOneDaysAgo));
        predicates.add(criteriaBuilder.equal(studentRoot.get("verificationStatus"), VerificationStatus.PENDING));

        if (courseId != null) {
            predicates.add(criteriaBuilder.equal(studentRoot.get("course").get("id"), courseId));
        }
        if (academicYear != null && !academicYear.trim().isEmpty()) {
            predicates.add(criteriaBuilder.equal(studentRoot.get("academicYear"), academicYear));
        } else if (academicYear != null) {
            predicates.add(criteriaBuilder.isNull(studentRoot.get("academicYear")));
        }

        criteriaQuery.where(predicates.toArray(new Predicate[0]));

        return entityManager.createQuery(criteriaQuery).getResultList();
    }

    public Student findById(Long id) {
        return studentRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Student with ID " + id + " not found"));
    }

    public Student updateVerificationStatus(Student student, VerificationStatus status) {
        student.setVerificationStatus(status);
        return studentRepository.save(student);
    }

    public Student verifyStudent(Long studentId, VerificationStatus status, String remarks) {
        Student student = this.findById(studentId);
        student.setVerificationStatus(status);
        student.setAdminRemarks(remarks);
        student.setVerificationDate(LocalDate.now());

        if (status == VerificationStatus.VERIFIED) {
            String enrollmentNumber = "EN" + LocalDate.now().getYear() + student.getCenterCode() + student.getId();
            student.setEnrollmentNumber(enrollmentNumber);
        } else if (status == VerificationStatus.REJECTED) {
            student.setRequiresRevision(true);
        }
        return this.save(student);
    }

    public List<Student> findByVerificationStatus(VerificationStatus status) {
        return studentRepository.findByVerificationStatus(status);
    }

    public Student save(Student student) {
        return studentRepository.save(student);
    }

    private boolean validateFile(Path path) throws IOException {
        if (Files.isExecutable(path)) {
            throw new RuntimeException("Invalid file");
        }
        Tika tika = new Tika();
        String fileExt = tika.detect(path);
        if (fileExt.equalsIgnoreCase("application/pdf")) {
            return true;
        } else if (fileExt.startsWith("image")) {
            return true;
        }
        throw new RuntimeException("Invalid file type. Supported files are PDF and Images.");
    }
    public ResponseDto deleteStudent(Long id) {
        ResponseDto response = new ResponseDto();
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Student not found for ID: " + id));

        try {
            // Initialize GCS Credentials and client
            ServiceAccountCredentials credentials = ServiceAccountCredentials.fromPkcs8(gcpClientId, gcpClientEmail,
                    gcpPkcsKey, gcpPrivateKeyId, new ArrayList<String>());
            Storage storage = StorageOptions.newBuilder()
                    .setProjectId(gcpProjectId)
                    .setCredentials(credentials)
                    .build()
                    .getService();

            // Delete each document from GCS
            for (String path : Arrays.asList(student.getHighSchoolMarksheetPath(), student.getHighSchoolCertificatePath(), student.getIntermediateMarksheetPath(), student.getIntermediateCertificatePath())) {
                BlobId blobId = BlobId.of(gcpBucketName, path);
                storage.delete(blobId);
            }

            // Delete student record or mark it as obsolete based on your requirements
            studentRepository.deleteById(id);

            response.put(Constants.MESSAGE, Constants.SUCCESSFUL);
            response.put(Constants.RESPONSE, "Student and associated documents deleted successfully");
            response.setResponseCode(HttpStatus.OK);
        } catch (Exception e) {
            response.put(Constants.MESSAGE, "Exception occurred during deleting the student");
            response.put(Constants.RESPONSE, e.getMessage());
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }
    private void deleteStudentDocument(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }

        try {
            // Initialize GCS Credentials and client
            ServiceAccountCredentials credentials = ServiceAccountCredentials.fromPkcs8(gcpClientId, gcpClientEmail,
                    gcpPkcsKey, gcpPrivateKeyId, new ArrayList<String>());
            Storage storage = StorageOptions.newBuilder()
                    .setProjectId(gcpProjectId)
                    .setCredentials(credentials)
                    .build()
                    .getService();

            BlobId blobId = BlobId.of(gcpBucketName, path);
            storage.delete(blobId);
        } catch (Exception e) {
            log.error("Error deleting document from GCS: " + path, e);
            throw new RuntimeException("Error deleting document: " + path, e);
        }
    }

}