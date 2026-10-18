-- =====================================================================================
-- Phase 2: bulk test data generator.
-- Creates :rows patients (default 100000) with EVERY column of all six patient tables
-- filled with realistic, mutually consistent values (age/title/marital status, city/state/
-- pincode/language, department/doctor/complaint, insurance by category, etc.).
--
-- Run (see load-data.bat):  psql -U postgres -v rows=100000 -v hospital=1 -f load-test-data.sql
-- All rows go to ONE hospital (default 1), with that hospital's departments and registration numbers
-- (CODE-REGyyyy0000001, continuing its counter). Run as the administrator (row-level security does not apply).
-- Runs in one transaction: on any error nothing is inserted. Re-running adds more rows.
-- Contact data is fictitious (emails use example.* domains).
-- =====================================================================================
\set ON_ERROR_STOP on
\if :{?rows}
\else
    \set rows 100000
\endif
\if :{?hospital}
\else
    \set hospital 1
\endif
\timing on

BEGIN;
SET LOCAL search_path TO patientreg;
-- the hospital must exist; its code prefixes the registration numbers
SELECT hospital_code AS hcode FROM identity.hospital WHERE hospital_id = :hospital \gset
SELECT setseed(0.2026 + :hospital * 0.01);   -- different names per hospital

-- ---------- helpers ----------
CREATE FUNCTION pg_temp.pick(a text[]) RETURNS text LANGUAGE sql VOLATILE AS
$$ SELECT a[1 + floor(random() * cardinality(a))::int] $$;

CREATE FUNCTION pg_temp.digits(k int) RETURNS text LANGUAGE sql VOLATILE AS
$$ SELECT lpad(floor(random() * (10::numeric ^ k))::bigint::text, k, '0') $$;

-- ---------- lookup data ----------
CREATE TEMP TABLE lk ON COMMIT DROP AS SELECT
  ARRAY['Aarav','Vivaan','Aditya','Arjun','Rohan','Rahul','Amit','Suresh','Ramesh','Rajesh','Vikram','Sanjay',
        'Anil','Sunil','Manoj','Deepak','Prakash','Ganesh','Mahesh','Nitin','Sachin','Vijay','Ajay','Kiran',
        'Harish','Karthik','Venkatesh','Srinivas','Arvind','Mohan','Pravin','Siddharth','Varun','Kunal','Yash',
        'Omkar','Tejas','Abhishek','Gaurav','Imran','Joseph','Gurpreet']::text[] AS male,
  ARRAY['Aadhya','Ananya','Diya','Priya','Pooja','Sneha','Kavya','Anjali','Neha','Swati','Sunita','Anita',
        'Kavita','Meena','Lakshmi','Radha','Geeta','Rekha','Shweta','Pallavi','Deepa','Divya','Shruti','Asha',
        'Usha','Savita','Madhuri','Vaishali','Aishwarya','Nandini','Revathi','Saranya','Bhavana','Komal',
        'Sakshi','Riya','Isha','Tanvi','Fatima','Sarah','Mary','Harpreet']::text[] AS female,
  ARRAY['Sharma','Patil','Kulkarni','Deshmukh','Joshi','Pawar','Shinde','Jadhav','Gupta','Verma','Singh',
        'Yadav','Reddy','Rao','Naidu','Iyer','Nair','Menon','Pillai','Shah','Patel','Mehta','Desai','Chauhan',
        'Banerjee','Chatterjee','Das','Ghosh','Mukherjee','Khan','Sheikh','D''Souza','Fernandes','Gill',
        'Sandhu','Kapoor','Malhotra','Agarwal','Bhat','Hegde']::text[] AS surname;

CREATE TEMP TABLE city (ci int PRIMARY KEY, city text, district text, state text, pin3 text, lang text) ON COMMIT DROP;
INSERT INTO city VALUES
 (1,'Pune','Pune','Maharashtra','411','Marathi'),
 (2,'Mumbai','Mumbai Suburban','Maharashtra','400','Marathi'),
 (3,'Nagpur','Nagpur','Maharashtra','440','Marathi'),
 (4,'Nashik','Nashik','Maharashtra','422','Marathi'),
 (5,'Delhi','New Delhi','Delhi','110','Hindi'),
 (6,'Lucknow','Lucknow','Uttar Pradesh','226','Hindi'),
 (7,'Jaipur','Jaipur','Rajasthan','302','Hindi'),
 (8,'Bhopal','Bhopal','Madhya Pradesh','462','Hindi'),
 (9,'Indore','Indore','Madhya Pradesh','452','Hindi'),
 (10,'Patna','Patna','Bihar','800','Hindi'),
 (11,'Ahmedabad','Ahmedabad','Gujarat','380','Gujarati'),
 (12,'Surat','Surat','Gujarat','395','Gujarati'),
 (13,'Bengaluru','Bengaluru Urban','Karnataka','560','Kannada'),
 (14,'Mysuru','Mysuru','Karnataka','570','Kannada'),
 (15,'Chennai','Chennai','Tamil Nadu','600','Tamil'),
 (16,'Coimbatore','Coimbatore','Tamil Nadu','641','Tamil'),
 (17,'Hyderabad','Hyderabad','Telangana','500','Telugu'),
 (18,'Visakhapatnam','Visakhapatnam','Andhra Pradesh','530','Telugu'),
 (19,'Kochi','Ernakulam','Kerala','682','Malayalam'),
 (20,'Thiruvananthapuram','Thiruvananthapuram','Kerala','695','Malayalam'),
 (21,'Kolkata','Kolkata','West Bengal','700','Bengali'),
 (22,'Ludhiana','Ludhiana','Punjab','141','Punjabi'),
 (23,'Amritsar','Amritsar','Punjab','143','Punjabi'),
 (24,'Bhubaneswar','Khordha','Odisha','751','Other'),
 (25,'Guwahati','Kamrup Metropolitan','Assam','781','Other'),
 (26,'Panaji','North Goa','Goa','403','English');

CREATE TEMP TABLE dept_data (code text PRIMARY KEY, doctors text[], complaints text[]) ON COMMIT DROP;
INSERT INTO dept_data VALUES
 ('GEN', ARRAY['Dr. Anil Kulkarni','Dr. Meera Iyer','Dr. Sanjay Gupta'],
         ARRAY['Fever with body ache','Cough and cold','Weakness and fatigue','Headache','Abdominal pain',
               'Loose motions','Vomiting','Routine health check-up']),
 ('CAR', ARRAY['Dr. Rakesh Mehta','Dr. Sunita Rao','Dr. Vivek Nair'],
         ARRAY['Chest pain','Palpitations','Breathlessness on exertion','High blood pressure','Swelling in legs']),
 ('ORT', ARRAY['Dr. Ajit Deshpande','Dr. Kavita Shah','Dr. Harish Reddy'],
         ARRAY['Knee pain','Lower back pain','Shoulder pain','Neck pain','Ankle sprain','Fracture follow-up']),
 ('PED', ARRAY['Dr. Neha Joshi','Dr. Arun Pillai','Dr. Smita Patil'],
         ARRAY['Fever','Cough and cold','Vaccination','Loose motions','Ear pain','Skin rash','Poor appetite']),
 ('GYN', ARRAY['Dr. Shalini Verma','Dr. Rekha Menon','Dr. Anjali Desai'],
         ARRAY['Antenatal check-up','Irregular periods','Lower abdominal pain','Postnatal check-up',
               'White discharge']),
 ('ENT', ARRAY['Dr. Prakash Bhat','Dr. Farah Khan','Dr. Manish Agarwal'],
         ARRAY['Ear pain','Sore throat','Blocked nose','Hearing loss','Tonsillitis','Sinusitis']),
 ('DER', ARRAY['Dr. Pooja Malhotra','Dr. Rohit Kapoor','Dr. Swati Hegde'],
         ARRAY['Skin rash','Itching','Acne','Hair fall','Fungal infection','Dandruff']),
 ('NEU', ARRAY['Dr. Vikram Singh','Dr. Lata Banerjee','Dr. Suresh Naidu'],
         ARRAY['Migraine','Dizziness','Numbness in hands','Seizure follow-up','Tremors']),
 ('OPH', ARRAY['Dr. Ramesh Chauhan','Dr. Divya Ghosh','Dr. Imran Sheikh'],
         ARRAY['Blurred vision','Red eye','Eye pain','Watering of eyes','Cataract evaluation',
               'Spectacle check-up']),
 ('DEN', ARRAY['Dr. Nikhil Pawar','Dr. Priya Fernandes','Dr. Gaurav Sandhu'],
         ARRAY['Toothache','Bleeding gums','Tooth sensitivity','Dental cleaning','Broken tooth']);

-- ---------- stage 1: core demographics ----------
CREATE TEMP TABLE g1 ON COMMIT DROP AS
SELECT n, reg_date, gender, age, ci, r_marital, r_cat, r_dept, r_cov,
       (reg_date - make_interval(years => age))::date - floor(random() * 360)::int AS dob   -- exactly "age" years old
FROM (
    SELECT n,
           current_date - floor(power(random(), 1.3) * 730)::int AS reg_date,   -- last 2 years, more recent
           CASE WHEN rg < 0.49 THEN 'Male' WHEN rg < 0.98 THEN 'Female' ELSE 'Other' END AS gender,
           CASE WHEN ra < 0.14 THEN floor(ra / 0.14 * 14)
                WHEN ra < 0.24 THEN 14 + floor((ra - 0.14) / 0.10 * 11)
                WHEN ra < 0.76 THEN 25 + floor((ra - 0.24) / 0.52 * 35)
                ELSE 60 + floor((ra - 0.76) / 0.24 * 31) END::int AS age,
           1 + floor(random() * 26)::int AS ci,
           random() AS r_marital, random() AS r_cat, random() AS r_dept, random() AS r_cov
    FROM (SELECT n, random() AS rg, random() AS ra FROM generate_series(1, :rows) n) s
) t;

-- ---------- stage 2: every remaining attribute ----------
CREATE TEMP TABLE g2 ON COMMIT DROP AS
SELECT x.*,
       -- title
       CASE WHEN age < 13 THEN CASE WHEN gender = 'Female' THEN 'Baby' ELSE 'Master' END
            WHEN age >= 28 AND random() < 0.04 THEN 'Dr.'
            WHEN gender = 'Male' THEN 'Mr.'
            WHEN gender = 'Female' AND marital = 'Married' THEN 'Mrs.'
            WHEN gender = 'Female' THEN 'Ms.'
            ELSE pg_temp.pick(ARRAY['Mr.','Ms.']) END AS title,
       -- emergency contact relationship
       CASE WHEN age < 18 THEN pg_temp.pick(ARRAY['Father','Mother'])
            WHEN marital = 'Married' AND random() < 0.8 THEN 'Spouse'
            WHEN age >= 60 THEN pg_temp.pick(ARRAY['Son','Daughter'])
            ELSE pg_temp.pick(ARRAY['Father','Mother','Brother','Sister','Friend']) END AS em_rel
FROM (
    SELECT g1.*,
           CASE WHEN gender = 'Female' THEN pg_temp.pick(lk.female) WHEN gender = 'Male' THEN pg_temp.pick(lk.male)
                ELSE pg_temp.pick(lk.male || lk.female) END AS first_name,
           pg_temp.pick(lk.male) AS middle_name,          -- father's / husband's name, Indian convention
           pg_temp.pick(lk.surname) AS last_name,
           CASE WHEN age < 18 THEN 'Single'
                WHEN age <= 25 THEN CASE WHEN r_marital < 0.70 THEN 'Single' ELSE 'Married' END
                WHEN age < 60 THEN CASE WHEN r_marital < 0.80 THEN 'Married' WHEN r_marital < 0.90 THEN 'Single'
                                        WHEN r_marital < 0.95 THEN 'Divorced' ELSE 'Widowed' END
                ELSE CASE WHEN r_marital < 0.70 THEN 'Married' WHEN r_marital < 0.98 THEN 'Widowed'
                          ELSE 'Divorced' END END AS marital
    FROM g1 CROSS JOIN lk
) x;

-- ---------- stage 3: final rows, ids assigned in registration-date order ----------
-- Registration numbers continue the hospital's counter of each year (hospital_reg_counter, updated below).
CREATE TEMP TABLE g ON COMMIT DROP AS
SELECT nextval('patient_patient_id_seq') AS pid,
       :'hcode' || '-REG' || to_char(o.reg_date, 'YYYY')
           || lpad((coalesce(rc.last_no, 0)
                    + row_number() OVER (PARTITION BY date_part('year', o.reg_date) ORDER BY o.reg_date, o.n))::text, 7, '0')
           AS reg_no,
       o.*
FROM (SELECT g2.*, c.city, c.district, c.state, c.pin3, c.lang,
             -- category
             CASE WHEN age >= 60 AND r_cat < 0.85 THEN 'SENIOR_CITIZEN'
                  WHEN r_cat < 0.85 THEN 'GENERAL' WHEN r_cat < 0.94 THEN 'BPL'
                  WHEN r_cat < 0.98 AND age BETWEEN 21 AND 59 THEN 'STAFF'
                  WHEN r_cat >= 0.98 AND age >= 21 THEN 'VIP' ELSE 'GENERAL' END AS category,  -- staff/VIP are adults
             -- department: children mostly paediatrics, women 18-45 often gynaecology, seniors cardio/ortho/eye/neuro
             CASE WHEN age < 14 AND r_dept < 0.75 THEN 'PED'
                  WHEN gender = 'Female' AND age BETWEEN 18 AND 45 AND r_dept < 0.25 THEN 'GYN'
                  WHEN age >= 60 AND r_dept < 0.35 THEN pg_temp.pick(ARRAY['CAR','ORT','OPH','NEU'])
                  ELSE pg_temp.pick(ARRAY['GEN','GEN','GEN','GEN','GEN','GEN','ORT','ORT','ENT','ENT','DER','DER',
                                          'OPH','OPH','DEN','DEN','CAR','NEU']) END AS dept_code,
             pg_temp.pick(ARRAY['NEW','NEW','NEW','NEW','NEW','NEW','NEW','FOLLOW_UP','FOLLOW_UP','EMERGENCY'])
                 AS visit_type
      FROM g2 JOIN city c USING (ci)) o
LEFT JOIN hospital_reg_counter rc ON rc.hospital_id = :hospital AND rc.reg_year = date_part('year', o.reg_date)
ORDER BY o.reg_date, o.n;

-- the hospital's counters now continue after the generated numbers
INSERT INTO hospital_reg_counter (hospital_id, reg_year, last_no)
SELECT :hospital, date_part('year', reg_date)::int, max(right(reg_no, 7)::int) FROM g GROUP BY 2
ON CONFLICT (hospital_id, reg_year) DO UPDATE SET last_no = greatest(hospital_reg_counter.last_no, EXCLUDED.last_no);

-- ---------- 1. patient ----------
INSERT INTO patient (hospital_id, patient_id, registration_no, registration_date, title, first_name, middle_name, last_name,
    gender, date_of_birth, age_years, blood_group, marital_status, nationality, religion, occupation, national_id,
    preferred_language, patient_category, status, created_at, updated_at)
SELECT :hospital, pid, reg_no, reg_date, title, first_name, middle_name, last_name, gender, dob,
       date_part('year', age(reg_date, dob))::int,
       pg_temp.pick(ARRAY['B+','B+','B+','O+','O+','O+','A+','A+','AB+','B-','O-','A-','AB-']),
       marital, 'Indian',
       pg_temp.pick(ARRAY['Hindu','Hindu','Hindu','Hindu','Hindu','Hindu','Hindu','Hindu','Hindu','Hindu','Hindu',
                          'Hindu','Muslim','Muslim','Christian','Sikh','Buddhist','Jain','Other']),
       CASE WHEN age < 5 THEN 'Child'
            WHEN age < 18 THEN 'Student'
            WHEN age < 23 AND random() < 0.7 THEN 'Student'
            WHEN title = 'Dr.' THEN 'Doctor'
            WHEN age >= 60 AND random() < 0.7 THEN 'Retired'
            WHEN gender = 'Female' AND random() < 0.3 THEN 'Homemaker'
            ELSE pg_temp.pick(ARRAY['Engineer','Teacher','Farmer','Business','Driver','Clerk','Nurse','Accountant',
                 'Shopkeeper','Software Developer','Labourer','Police Officer','Electrician','Tailor',
                 'Bank Officer','Government Employee','Sales Executive','Self Employed','Mechanic','Pharmacist'])
       END,
       (2 + floor(random() * 8))::int::text || pg_temp.digits(11),
       CASE WHEN random() < 0.75 THEN lang ELSE pg_temp.pick(ARRAY['English','Hindi']) END,
       category, 'ACTIVE',
       reg_date + make_interval(hours => 8 + floor(random() * 10)::int, mins => floor(random() * 60)::int),
       reg_date + make_interval(hours => 18 + floor(random() * 4)::int, mins => floor(random() * 60)::int)
FROM g;

-- ---------- 2. patient_contact ----------
INSERT INTO patient_contact (hospital_id, patient_id, mobile, alternate_phone, email, address_line1, address_line2, city,
    district, state, pincode, country, perm_same_as_current, permanent_address)
SELECT :hospital, pid, mobile, pg_temp.pick(ARRAY['9','8','7','6']) || pg_temp.digits(9),
       regexp_replace(lower(first_name || '.' || last_name || (n % 997)::text), '[^a-z0-9.]', '', 'g')
           || '@' || pg_temp.pick(ARRAY['example.com','example.in','mail.example.org']),
       line1, line2, city, district, state, pincode, 'India', same,
       CASE WHEN same THEN line1 || ', ' || line2 || ', ' || city || ', ' || state || ' - ' || pincode
            ELSE 'At Post ' || pg_temp.pick(ARRAY['Wadgaon','Rampur','Kesarpur','Sonpur','Malegaon','Chandanpur',
                     'Devgaon','Shivpur','Navapur','Ambegaon']) || ', Tal. ' || pg_temp.pick(ARRAY['Haveli','Mulshi',
                     'Khed','Baramati','Junnar','Shirur']) || ', ' || pg_temp.pick(ARRAY['Maharashtra','Karnataka',
                     'Uttar Pradesh','Bihar','Rajasthan','Madhya Pradesh']) || ' - ' || (1 + floor(random() * 8))::int::text
                     || pg_temp.digits(5) END
FROM (SELECT g.*,
             pg_temp.pick(ARRAY['9','8','7','6']) || pg_temp.digits(9) AS mobile,
             pg_temp.pick(ARRAY['Flat ','House No. ','Plot ','Room ']) || (1 + floor(random() * 450))::int::text
                 || ', ' || pg_temp.pick(ARRAY['Shanti Nagar','MG Road','Station Road','Gandhi Chowk','Nehru Colony',
                 'Shivaji Nagar','Laxmi Vihar','Sai Residency','Green Park','Rajendra Nagar','Ashok Vihar',
                 'Sector 12','Model Town','Civil Lines','Indira Colony','Krishna Apartments']) AS line1,
             pg_temp.pick(ARRAY['Near City Hospital','Opp. Bus Stand','Behind Post Office','Near Railway Station',
                 'Near Hanuman Temple','Opp. Municipal School','Near Main Market','Next to SBI Bank',
                 'Near Water Tank','Behind Police Station']) AS line2,
             pin3 || pg_temp.digits(3) AS pincode,
             random() < 0.8 AS same
      FROM g) c;

-- ---------- 3. patient_emergency_contact ----------
INSERT INTO patient_emergency_contact (hospital_id, patient_id, contact_name, relationship, phone, alternate_phone, address)
SELECT :hospital, g.pid,
       CASE WHEN em_rel IN ('Father','Brother','Son') OR (em_rel = 'Spouse' AND gender <> 'Male')
                THEN pg_temp.pick(lk.male)
            WHEN em_rel IN ('Mother','Sister','Daughter') OR em_rel = 'Spouse' THEN pg_temp.pick(lk.female)
            ELSE pg_temp.pick(lk.male || lk.female) END
           || ' ' || CASE WHEN em_rel = 'Friend' THEN pg_temp.pick(lk.surname) ELSE g.last_name END,
       em_rel,
       pg_temp.pick(ARRAY['9','8','7','6']) || pg_temp.digits(9),
       pg_temp.pick(ARRAY['9','8','7','6']) || pg_temp.digits(9),
       CASE WHEN em_rel = 'Friend' OR random() < 0.15
            THEN (1 + floor(random() * 300))::int::text || ', ' || pg_temp.pick(ARRAY['Tilak Road','Camp Area',
                 'Kothrud','Laxmi Road','Sadar Bazar','Old City']) || ', ' || g.city
            ELSE c.address_line1 || ', ' || c.address_line2 || ', ' || g.city END
FROM g CROSS JOIN lk JOIN patient_contact c ON c.patient_id = g.pid;

-- ---------- 4. patient_insurance (everyone covered: BPL via government scheme) ----------
INSERT INTO patient_insurance (hospital_id, patient_id, has_insurance, provider_name, policy_number, group_number,
    policy_holder_name, holder_relationship, valid_from, valid_to, coverage_type, sum_insured)
SELECT :hospital, pid, TRUE, provider,
       upper(left(regexp_replace(provider, '[^A-Za-z]', '', 'g'), 3)) || '/' || to_char(valid_from, 'YYYY') || '/'
           || pg_temp.digits(8),
       CASE coverage WHEN 'Corporate' THEN 'GRP-' || pg_temp.digits(5)
                     WHEN 'Government Scheme' THEN 'PMJAY-' || pg_temp.digits(6)
                     ELSE 'IND-' || pg_temp.digits(5) END,
       CASE holder_rel WHEN 'Self' THEN first_name || ' ' || last_name
                       WHEN 'Employer' THEN pg_temp.pick(ARRAY['Sunrise Industries Ltd','Metro Logistics Pvt Ltd',
                            'Bharat Engineering Works','Infotech Solutions Pvt Ltd','National Textiles Ltd',
                            'Hospital Staff Welfare Trust'])
                       ELSE em_name END,
       holder_rel, valid_from, valid_from + 364, coverage,
       CASE coverage WHEN 'Government Scheme' THEN 500000
            ELSE pg_temp.pick(ARRAY['200000','300000','500000','500000','1000000','1500000','2500000'])::numeric END
FROM (SELECT g.*, e.contact_name AS em_name,
             reg_date - floor(random() * 330)::int AS valid_from,
             CASE WHEN category = 'BPL' THEN 'Ayushman Bharat PM-JAY'
                  ELSE pg_temp.pick(ARRAY['Star Health Insurance','HDFC ERGO','ICICI Lombard','Niva Bupa',
                       'Care Health Insurance','New India Assurance','United India Insurance','Bajaj Allianz',
                       'Tata AIG','Aditya Birla Health']) END AS provider,
             CASE WHEN category = 'BPL' THEN 'Government Scheme'
                  WHEN category = 'STAFF' OR (age BETWEEN 22 AND 59 AND r_cov < 0.15) THEN 'Corporate'
                  WHEN age < 18 OR r_cov < 0.55 THEN 'Family Floater'
                  ELSE 'Individual' END AS coverage
      FROM g JOIN patient_emergency_contact e ON e.patient_id = g.pid) i
CROSS JOIN LATERAL (SELECT CASE
             WHEN age < 18 THEN 'Parent'                    -- children are covered under a parent's policy
             WHEN coverage = 'Corporate' THEN 'Employer'
             WHEN coverage = 'Family Floater' AND em_rel = 'Spouse' AND random() < 0.5 THEN 'Spouse'
             WHEN coverage = 'Family Floater' AND em_rel IN ('Son','Daughter') THEN 'Child'
             ELSE 'Self' END AS holder_rel) h;

-- ---------- 5. patient_medical_info ----------
INSERT INTO patient_medical_info (hospital_id, patient_id, height_cm, weight_kg, bmi, known_allergies, chronic_conditions,
    current_medications, past_surgeries, family_history, smoking_status, alcohol_use, disability, vaccination_status)
SELECT :hospital, pid, h, w, round(w / ((h / 100) ^ 2), 1),
       CASE WHEN random() < 0.8 THEN 'None known'
            ELSE pg_temp.pick(ARRAY['Penicillin','Sulfa drugs','Peanuts','Dust','Pollen','Seafood','Lactose',
                 'Aspirin','Latex','Eggs']) END,
       chronic,
       CASE chronic WHEN 'None' THEN 'None'
            WHEN 'Hypertension' THEN 'Amlodipine 5 mg once daily'
            WHEN 'Type 2 Diabetes' THEN 'Metformin 500 mg twice daily'
            WHEN 'Hypertension, Type 2 Diabetes' THEN 'Telmisartan 40 mg, Metformin 500 mg'
            WHEN 'Asthma' THEN 'Salbutamol inhaler as needed'
            WHEN 'Hypothyroidism' THEN 'Levothyroxine 50 mcg daily'
            WHEN 'Arthritis' THEN 'Paracetamol 650 mg as needed'
            WHEN 'COPD' THEN 'Tiotropium inhaler daily'
            WHEN 'Coronary Artery Disease' THEN 'Aspirin 75 mg, Atorvastatin 20 mg'
            ELSE 'As advised by nephrologist' END,
       CASE WHEN random() < 0.75 THEN 'None'
            ELSE pg_temp.pick(CASE WHEN gender = 'Female' AND age >= 20
                 THEN ARRAY['Appendectomy','Caesarean section','Cataract surgery','Gallbladder removal','Hysterectomy']
                 ELSE ARRAY['Appendectomy','Hernia repair','Tonsillectomy','Cataract surgery','Knee arthroscopy',
                            'Gallbladder removal'] END)
                 || ' (' || (extract(year FROM reg_date)::int - 1 - floor(random() * least(age, 15))::int) || ')' END,
       pg_temp.pick(ARRAY['No significant family history','No significant family history',
            'No significant family history','Diabetes (father)','Hypertension (mother)','Heart disease (father)',
            'Cancer (grandmother)','Asthma (sibling)','Thyroid disorder (mother)','Diabetes (both parents)']),
       CASE WHEN age < 18 THEN 'Never'
            ELSE pg_temp.pick(ARRAY['Never','Never','Never','Never','Never','Never','Never','Never','Former',
                 'Former','Current','Current','Current']) END,
       CASE WHEN age < 18 THEN 'Never'
            ELSE pg_temp.pick(ARRAY['Never','Never','Never','Never','Never','Never','Occasional','Occasional',
                 'Occasional','Regular']) END,
       CASE WHEN random() < 0.95 THEN 'None'
            ELSE pg_temp.pick(ARRAY['Locomotor disability','Visual impairment','Hearing impairment',
                 'Speech impairment']) END,
       pg_temp.pick(ARRAY['Fully Vaccinated','Fully Vaccinated','Fully Vaccinated','Fully Vaccinated',
            'Fully Vaccinated','Fully Vaccinated','Fully Vaccinated','Partially Vaccinated','Partially Vaccinated',
            'Not Vaccinated','Unknown'])
FROM (SELECT g.*,
             round(CASE WHEN age < 18 THEN 50 + age * 6.4 + random() * 8
                        WHEN gender = 'Female' THEN 145 + random() * 27
                        ELSE 155 + random() * 30 END::numeric, 1) AS h,
             CASE WHEN random() < CASE WHEN age < 30 THEN 0.10 WHEN age < 60 THEN 0.35 ELSE 0.70 END
                  THEN pg_temp.pick(ARRAY['Hypertension','Hypertension','Type 2 Diabetes','Type 2 Diabetes',
                       'Hypertension, Type 2 Diabetes','Asthma','Hypothyroidism','Arthritis','COPD',
                       'Coronary Artery Disease','Chronic Kidney Disease'])
                  ELSE 'None' END AS chronic
      FROM g) m
CROSS JOIN LATERAL (SELECT round(CASE WHEN age < 18 THEN 3.5 + age * 2.9 + random() * 4
                                      ELSE (18 + random() * 15) * (h / 100) ^ 2 END::numeric, 1) AS w) wt;

-- ---------- 6. patient_visit (the registration visit) ----------
INSERT INTO patient_visit (hospital_id, patient_id, dept_id, consulting_doctor, visit_type, visit_date, referred_by,
    referral_source, chief_complaint, symptom_duration, priority, payment_mode, registration_fee, remarks,
    registration_visit)
SELECT :hospital, pid, d.dept_id, pg_temp.pick(dd.doctors), visit_type, reg_date,
       CASE src WHEN 'Self' THEN 'Self'
                WHEN 'Doctor' THEN 'Dr. ' || pg_temp.pick(lk.male || lk.female) || ' ' || pg_temp.pick(lk.surname)
                                   || ' (Family physician)'
                WHEN 'Hospital' THEN pg_temp.pick(ARRAY['District Civil Hospital','Primary Health Centre',
                                   'Community Health Centre','Municipal Hospital']) || ', ' || g.city
                WHEN 'Health Camp' THEN 'Free Health Camp, ' || g.city
                WHEN 'Online' THEN pg_temp.pick(ARRAY['Hospital website','Online appointment portal','Mobile app'])
                ELSE pg_temp.pick(ARRAY['Relative','Neighbour','Employer']) END,
       src,
       pg_temp.pick(dd.complaints),
       CASE WHEN visit_type = 'EMERGENCY' THEN pg_temp.pick(ARRAY['Few hours','1 day'])
            ELSE pg_temp.pick(ARRAY['1 day','2 days','3 days','1 week','2 weeks','1 month','3 months','6 months'])
       END,
       CASE WHEN visit_type = 'EMERGENCY' THEN pg_temp.pick(ARRAY['URGENT','URGENT','CRITICAL'])
            WHEN random() < 0.9 THEN 'NORMAL' ELSE 'URGENT' END,
       CASE category WHEN 'BPL' THEN 'INSURANCE' WHEN 'STAFF' THEN 'CREDIT'
            ELSE pg_temp.pick(ARRAY['CASH','CASH','CASH','UPI','UPI','UPI','UPI','CARD','CARD','INSURANCE',
                 'INSURANCE','CREDIT']) END,
       CASE WHEN category IN ('BPL','STAFF') THEN 0
            WHEN category = 'VIP' THEN 1000
            WHEN category = 'SENIOR_CITIZEN' THEN 100
            ELSE CASE WHEN g.dept_code = 'GEN' THEN 200 ELSE 400 END END
           + CASE WHEN visit_type = 'EMERGENCY' AND category NOT IN ('BPL','STAFF') THEN 300 ELSE 0 END,
       pg_temp.pick(ARRAY['Medicines prescribed','Advised blood tests','Advised X-ray','Referred for ECG',
            'Follow-up after 1 week','Diet counselling given','Advised rest','Reports reviewed',
            'Dressing done','Review with reports']),
       TRUE
FROM (SELECT g.*, pg_temp.pick(ARRAY['Self','Self','Self','Self','Self','Doctor','Doctor','Hospital',
             'Health Camp','Online','Online','Other']) AS src FROM g) g
CROSS JOIN lk
JOIN department d ON d.dept_code = g.dept_code AND d.hospital_id = :hospital
JOIN dept_data dd ON dd.code = g.dept_code;

COMMIT;

ANALYZE patientreg.patient, patientreg.patient_contact, patientreg.patient_emergency_contact,
        patientreg.patient_insurance, patientreg.patient_medical_info, patientreg.patient_visit;

SELECT count(*) AS total_patients FROM patientreg.patient;
