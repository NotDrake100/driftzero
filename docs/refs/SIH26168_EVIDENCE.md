# SIH26168 evidence log

Purpose: source of record for claims in `docs/07_RESEARCH_AND_ROADMAP.md` and `docs/08_PRODUCT_DESIGN.md`. Those files tag unverified web claims `[pending verification]`. Resolve them from this page. Do not treat this file as a product spec.

Retrieval date: 2026-09-03.

Method: official `sih.gov.in` HTML was downloaded with a browser User-Agent after `WebFetch` returned 403. Other pages were opened with `WebFetch`, arXiv HTML or PDF, GitHub raw files, or seen only in search-result snippets. A fact appears below only if it was seen in this session.

Verification legend:

| Status | Meaning |
|---|---|
| fetched | The page, PDF, PPTX, or source file was opened in this session. |
| search snippet | Seen only in a search-result title, abstract, or highlight. The full page was not opened, or the official page timed out. |
| could not verify | Looked for and not found, blocked, timed out, or returned the wrong document. |

Official SIH26168 wording uses the sponsor's own phrases (including "intelligent", "seamless", and "AI-ML"). Those words appear only inside quoted official text.

Existing literature notes in `docs/refs/DATASETS.md`, `INS_ESKF.md`, `LEARNED_IMU.md`, `MAP_MATCHING.md`, and `NAVIC.md` are not copied here. This file is the independent fetch log.

---

## A. Official SIH26168 problem statement

**Primary source (fetched):** `https://www.sih.gov.in/sih2026PS`, modal `#ViewProblemStatement26168`, retrieved 2026-09-03. Table row for this ID: Software, `SIH26168`, idea counter `0/500` at retrieval, theme Smart Vehicles, deadline 20 September 2026.

**Cross-check (fetched):** community archive `https://sih2026.vuce.in/ps/SIH26168` (200). Body matches the official modal.

**Dataset link on the official modal (fetched):** `IO-VNBD: Inertial and Odometry benchmark dataset for ground vehicle positioning (https://github.com/onyekpeu/IO-VNBD)`.

### Confirmed identity

| Field | Official value | Status |
|---|---|---|
| Problem Statement ID | 26168 | fetched |
| Portal code | SIH26168 | fetched |
| Title | AI-ML based Intelligent Dead Reckoning system for seamless navigation | fetched |
| Organization | Indian Space Research Organisation(ISRO) | fetched |
| Department | Department of Space / Indian Space Research Organisation | fetched |
| Category | Software | fetched |
| Theme | Smart Vehicles | fetched |
| Idea deadline on the PS table | 20 September 2026 | fetched |
| Idea cap at retrieval | 0/500 | fetched |

### Failure environments (confirmed)

Verbatim from the official Description:

> "when a vehicle enters a long underground tunnel/ underpass, a multi-level parking lot, a dense forested highway, or a deep urban canyon surrounded by skyscrapers, GNSS connectivity drops entirely."

> "GNSS signals are inherently weak and vulnerable to structural blockage (urban canyons, dense foliage, tunnels, deep valleys) and unintentional electromagnetic interferences from variety of sources such as jamming."

Jamming is named. Spoofing is not named on the official page. Status: fetched.

### MEMS disturbance (confirmed)

Verbatim:

> "The smartphone is subjected to severe chassis vibrations, engine harmonics, sudden braking, and road potholes."

Status: fetched.

### OBD and speedometer (confirmed, hard prohibition)

Verbatim:

> "Without an external speedometer feed from the vehicle's OBD-II port, calculating distance and velocity exclusively from consumer-grade smartphone sensors results in exponential error accumulation"

> "maintaining lane-level accuracy without requiring any physical connection to the vehicle’s internal computer"

This is a hard phone-path prohibition of an OBD-II or vehicle-computer connection, not a soft "should not depend on". Status: fetched.

### Lane-level verb (confirmed)

The Description says "maintaining lane-level accuracy". It does not say "aim for". Status: fetched.

### Lightweight engine and mobile app (confirmed)

Verbatim:

> "The goal is to develop a lightweight, edge-deployable software engine and mobile application that transforms a standalone smartphone into an Intelligent Dead Reckoning (IDR) system with GNSS Fusion."

Status: fetched.

### Speed, vibration, misalignment (confirmed)

Verbatim:

> "the solution must employ AI/ML models trained on vehicle kinematics to accurately predict vehicle speed and acceleration profiles solely from the smartphone’s noisy accelerometer/gyro inputs. It must dynamically detect and filter out non-navigation motions such as engine idling vibrations, pothole shocks, bumps, and accidental phone misalignments on the mount."

Status: fetched.

### Offline maps and NHC (confirmed)

Verbatim:

> "By overlaying the inertial trajectory onto an offline map database (e.g., Open Street Map), the system should use the road layout as a constraint. For instance, it can apply Non-Holonomic Constraints (NHC), assuming a car cannot slide sideways or fly upwards"

Open Street Map is named. Matcher example later: "AI-ML framework or Unscented Kalman Filter + Hidden Markov Map Matching". Status: fetched.

### External IMU and FOG (confirmed)

Verbatim:

> "These algorithms/models should also work with any other external IMU sensors data (Edge deployable software engine)."

> "Position update rate of 10Hz with processing on smartphones (Mobile application) and higher update rates on Edge deployable software engine using FOG based IMU sensors data (around 200Hz)."

FOG is named. About 200 Hz is named. The PS requires algorithms that accept external IMU data. It does not say a live FOG unit must be demonstrated at screening. Finale text says the trained model "will need to be exported to the smartphone" and receive live phone IMU and GNSS. Status: fetched.

### Mandatory dataset and screening plot (confirmed)

Verbatim:

> "This dataset should be used to train & test the models and submit for screening of proposals. Teams are required to include the preliminary AI models and the results of the position plot inferenced from the subset of IO-VNBD dataset as part of their proposals submitted for evaluation. During the screening process more datasets will be provided for further evaluation of the AI models."

The official page does not define whether "preliminary AI models" must be a neural network. The Expected Solution allows "A deep-learning or statistical signal-processing model" for the speed filter. Status: fetched.

### Training versus inference (confirmed)

Verbatim:

> "Complex training happens in the cloud/desktop apriori, while inference happens on the smartphone."

No foundation-model wording and no TimesFM wording appear in the official PS text. Status: fetched.

### Named inputs (confirmed)

Verbatim:

> "It should receive live inputs from the phone's built-in Inertial Measurement Unit (IMU)—the accelerometer, gyroscope, and magnetometer/compass and GNSS data if available."

Magnetometer/compass is a named input. Status: fetched.

### Expected solution modules (confirmed, verbatim names)

1. In-Vehicle Alignment & Calibration Engine
2. AI Speed & Vibration Filter
3. Advanced Map-Matching & Kinematic Constraints
4. GNSS+INS Fusion Engine
5. Seamless GNSS Deficit Handler
6. Real-time Navigation Interface

Status: fetched.

### Performance benchmark (confirmed)

Verbatim:

> "Dead Reckoning: The solution must restrict positional drift to less than 10% of the total distance travelled using smartphone IMUs sensors during GNSS signals blackout (for e.g., in case of smartphones IMU, a drift of less than 5 meters is desired over 50m GNSS denied environment in <1 minutes OR less than 100m of drift over a 1km GNSS denied environment at a speed of 60kmph in tunnels/underground metro OR similar simulated environments where GNSS signals are unavailable)."

> "GNSS+INS Fusion: Position update rate of 10Hz with processing on smartphones (Mobile application) and higher update rates on Edge deployable software engine using FOG based IMU sensors data (around 200Hz)."

Reading: "must restrict" applies to the 10 percent figure. The 5 m / 50 m and 100 m / 1 km figures are introduced by "for e.g." and "is desired". Status: fetched.

### What section A could not confirm from the official PS page

- Official 2026 screening format beyond "preliminary AI models" and "position plot inferenced from the subset of IO-VNBD" plus the idea PPT PDF (see section B).
- Official 2026 grand-finale scoring weights.
- Whether a live external FOG IMU must be present at the finale (PS requires the engine to accept external IMU data; finale text emphasises the smartphone).
- Whether "AI model" excludes a filter plus a learned or statistical speed component.

---

## B. SIH 2026 process and judging

### Official 2026 guidelines (fetched)

`https://www.sih.gov.in/letters/2026/SIH%202026%20Guidelines.pdf` (26 pages, downloaded 2026-09-03 from the home-page link `/letters/2026/SIH 2026 Guidelines.pdf`).

Confirmed from that PDF:

- SPOC (faculty) registers students. Students do not register themselves.
- Only students selected in an internal hackathon may be nominated.
- Institute cap: 50 teams (45 shortlisted + 5 waitlisted). University cap: 100 teams.
- Team: 6 members including the leader, same college, at least one female member. No inter-college teams.
- Software edition: "programming skills are crucial."
- Each team may submit ideas against a maximum of 2 problem statements.
- Idea counter starts August 2026. The PDF says only 500 ideas will be submitted for a particular PS. Once that count is reached, the PS freezes. Official table showed `0/500` for SIH26168 on 2026-09-03.
- Deadline: "last date for team nomination and idea submission ... is till 20th Sept 2026 only."
- Team leader uploads: idea title, idea description, idea presentation (PDF).
- "4-5 teams per problem statement may be selected for the grand finale, but the final decision rests with the problem statement creating organization, which isn't obligated to declare a winner unless student proposals meet their expectations."
- Idea selection criteria (no numeric weights in the PDF): "novelty of the idea, complexity, clarity and details in the prescribed format, feasibility, practicability, sustainability, scale of impact, user experience and potential for future work progression."
- Grand Finale: "held offline at various nodal centers across pan India". "proposed to be organized in December 2026".
- Mentors: up to 2, optional, 5 years industry or academic experience. Mentors help "convert students' ideas into a working prototype".
- Prize: Rs 1,50,000 per problem statement, paid only if the organisation likes the idea. One winning team per PS is the default, not mandatory.
- IP of a winning idea is split equally between the organisation and the team, or by mutual agreement.

The official guidelines list "Idea presentation (PDF)" as the screening artefact. They do not require a short video as part of idea submission. An Image/Video Link appears only in the internal-hackathon report fields for the SPOC.

Status: fetched.

### Official 2026 idea PPT template (fetched)

`https://www.sih.gov.in/letters/2026/SIH2026-IDEA-Presentation-Format.pptx` (downloaded 2026-09-03). Seven slides in the file. Slide 7 is instructions and says it may be deleted.

Slide titles and required pointers:

1. TITLE PAGE: Problem Statement ID, Problem Statement Title, Theme, PS Category Software/Hardware, Team ID, Team Name.
2. IDEA TITLE / Proposed Solution: detailed explanation, how it addresses the problem, innovation and uniqueness.
3. TECHNICAL APPROACH: technologies, methodology and process (flow charts / images / working prototype).
4. FEASIBILITY AND VIABILITY: feasibility, challenges and risks, strategies for overcoming them.
5. IMPACT AND BENEFITS: impact on the target audience, benefits.
6. RESEARCH AND REFERENCES: details / links of the reference and research work.
7. IMPORTANT INSTRUCTIONS: "Kindly keep the maximum slides limit up to six (6). (Including the title slide)". Upload as PDF only. Use the provided template without changing the idea-detail pointers.

Status: fetched.

### Official FAQ (fetched)

`https://www.sih.gov.in/faqs`, retrieved 2026-09-03 with a browser User-Agent.

- Ninth edition. Lists SIH2017 through SIH2025 as completed editions.
- Team formation matches the 2026 guidelines (6 members, one female, same college).
- "First stage i.e. idea screening is online. SIH Grand Finale would be conducted in an offline mode."
- Prize money INR 1.5 Lakh for Hardware and Software.
- Internal hackathon is mandatory. Institutes need not wait for official PS launch to run it.
- FAQ Q.1 for teams: HEI may nominate "45+05 teams/colleges" and university "100 Team". Q.4 later says "50 teams/colleges". The 2026 guidelines PDF is the clearer 45+5 / 50 / 100 statement.

Status: fetched.

### Launch schedule (secondary, fetched)

Times Now, 21 August 2026, citing the official launch presentation: `https://www.timesnownews.com/education/smart-india-hackathon-2026-over-220-problem-statements-released-article-155949432`

| Activity | Timeline stated |
|---|---|
| Launch, PS announcement, idea/solution submission | 21 August to 20 September 2026 |
| Evaluation | 10 September to 30 October 2026 |
| Result announcement | First week of November 2026 |
| Training/mentoring for shortlisted finale teams | 10 to 30 November 2026 |
| Grand Finale | December 2026 (tentative) |

Also stated: 226 problem statements in the first lot (172 software, 54 hardware), 17 themes including Smart Vehicles, 27 ministries, 12 PSUs/corporates, 2 state governments. Status: fetched (press report of the launch, not a `sih.gov.in` PDF of the slide deck).

### What section B could not verify

- Numeric weights for 2026 screening or finale scoring. The official PDF lists criteria names only.
- A mandatory screening video. Official idea upload is a PDF of the 6-slide template.
- Whether the finale requires a live phone demo versus a recorded demo. Official text requires travel to a nodal centre and a working prototype. The PS says the model must run on the smartphone at the finale. It does not say "recorded demos are accepted" or "recorded demos are forbidden".
- Official 2026 evaluation rubric used by ISRO judges at the nodal centre.

---

## C. IO-VNBD dataset

### Paper (fetched)

Onyekpe, Uche; Palade, Vasile; Kanarachos, Stratis; Szkolnik, Alicja. "IO-VNBD: Inertial and odometry benchmark dataset for ground vehicle positioning." *Data in Brief* 35 (2021) 106885.

- arXiv: `https://arxiv.org/abs/2005.01701` (v2, 11 December 2021). Status: fetched.
- Journal DOI: `https://doi.org/10.1016/j.dib.2021.106885`. ScienceDirect HTML was blocked in this session. The arXiv PDF was downloaded and read: `https://arxiv.org/pdf/2005.01701.pdf`. Status: fetched.
- Author list on the PDF and arXiv: Onyekpe, Palade, Kanarachos, Szkolnik. Christopoulos is not an author of this paper.
- Related research article named in the specifications table: Onyekpe, Palade, Kanarachos, "Learning to Localise Automated Vehicles in Challenging Environments using Inertial Navigation Systems (INS)", *Applied Sciences* 2021, 11(3), 1270, `https://doi.org/10.3390/app11031270`. That is a separate paper. arXiv 2005.01701 is the dataset paper itself, not a separate speed-estimation paper.

### Repository (fetched)

`https://github.com/onyekpeu/IO-VNBD` and `https://raw.githubusercontent.com/onyekpeu/IO-VNBD/master/README.md`. About 40 stars at retrieval. README reprints the abstract. GitHub has no LICENSE file in the fetched README. Preferred sync folder name in the paper is "Synchronised V and S datasets". The repo's on-disk spelling "Synchronised V abd S datasets" is already noted in `docs/refs/DATASETS.md`.

### Scale, places, phones, rates (fetched from the PDF)

- About 40 h / 1,300 km vehicle-extracted (`V-`) data. About 58 h / 4,400 km smartphone (`S-`) data. About 100 h total driving by 8 drivers.
- Countries: United Kingdom (England), Nigeria, France. `V-` only in England. `S-` in England, France, and Nigeria.
- Phones: Huawei P20 Pro (main tables A1 to A5), also Motorola Moto G7 Power and BlackBerry Priv (Table A7, drivers F/G/H).
- Logging app: AndroSensor, 10 Hz. Phone GPS update rate 1 Hz.
- Vehicle logger: Racelogic VBOX Video HD2 CAN logger at 10 Hz, GPS antenna 10 Hz.
- Phone table shape about 2.2 million × 24. Vehicle table about 1.4 million × 29.
- Ethics: Coventry University Ethics Board, Project ID P95615.

### Table 5 units (fetched). Table A6 is not a units table

`docs/07` asked for Table A6 GPS SPEED units. In the paper, **Table 5** is "Information recorded from the smartphone sensors." **Tables A6-1 / A6-2** describe `V-Vfa` trip metadata (cities, weather, distance). They are not unit tables.

Table 5 column 4, verbatim: `GPS speed` unit `km/hr`.

Other Table 5 units fetched: GPS lat/lon degrees, altitude m, GPS accuracy m, GPS orientation degrees, time since start ms, accelerometer X/Y/Z m/s², gravity X/Y/Z m/s², Gyroscope (Yaw/Pitch/Roll) rad/s, magnetic field µT, orientation yaw/pitch/roll degrees.

The local empirical finding in `docs/07` (CSV values behave as m/s versus finite-difference GNSS) was not re-measured in this session. The paper's declared unit is km/hr. Do not treat this file as having confirmed or denied the m/s empirical ratio.

### Gyro axes and alignment (fetched)

The paper labels gyro columns "Gyroscope (Yaw)", "Gyroscope (Pitch)", "Gyroscope (Roll)" in rad/s. It cites AndroSensor. It does not publish an Android `TYPE_GYROSCOPE` axis-to-column map.

Verbatim:

> "despite the effort lent towards an accurate alignment of the smartphone's sensor axis with that of the vehicle, the precision of the measurements were interfered by vehicular vibrations averagely estimated to be about 0.15 g of acceleration and 0.08 rad/s of yaw rate particularly at peculiar scenarios such as hard brakes or over bumps. Information on the amount of gravitational acceleration measured by each of the three axis are provided in the “S-” datasets to help in the correction of the measured acceleration."

Gravity columns are provided for acceleration correction. That supports per-trip gravity alignment. It does not prove which named gyro column is vehicle yaw.

### Synchronisation (fetched)

> "Where possible, the “S-“ and “V-“ datasets which were collected simultaneously, are manually synchronised and stored in the folder named “Synchronised V and S datasets”."

Not every S/V pair is simultaneous. Sync is manual.

### License (mixed)

- Data in Brief articles are published open access under CC BY 4.0 on Elsevier's site. The ScienceDirect HTML was blocked this session, so the license line on the journal page is **search snippet / prior repo note**, not re-fetched.
- The GitHub repo README fetched here has no license block.
- Cite the journal article and the GitHub URL. Do not claim a GitHub LICENSE file exists.

### WhONet

WhONet (wheel-odometry network) was not opened in this session. **could not verify.**

---

## D. Literature

Each row: why DriftZero cites it, then source and status. Numeric KITTI or phone scores below are quoted from the paper that was opened, not from memory.

### Core inertial and vehicle papers

| Work | Citation | Why DriftZero cites it | URL | Status |
|---|---|---|---|---|
| AI-IMU Dead-Reckoning | Brossard, Barrau, Bonnabel. IEEE Transactions on Intelligent Vehicles, vol. 5, no. 4, pp. 585–595, December 2020. arXiv 1904.06064. | Learns Kalman noise for IMU-only vehicle DR with kinematic pseudo-measurements. Results are on KITTI, not a phone. Paper quotes "on average a 1.10% translational error". | https://arxiv.org/abs/1904.06064 ; IEEE snippet https://ieeexplore.ieee.org/document/9035481 | fetched (arXiv). Journal volume/pages: search snippet |
| Denoising IMU Gyroscopes | Brossard, Bonnabel, Barrau. IEEE RA-L (manuscript dates 24 Feb / 20 Apr / 8 Jun 2020). arXiv 2002.10718. Code `mbrossar/denoise-imu-gyro`. | Learned gyro correction, then open-loop attitude. EuRoC and TUM-VI, not phone-in-car. | https://arxiv.org/abs/2002.10718 | fetched |
| TLIO | Liu, Caruso, Ilg, Dong, Mourikis, Daniilidis, Kumar, Engel. Tight Learned Inertial Odometry. Pedestrian headset IMU. Network regresses 3D displacement **and covariance**, fused in a tightly coupled EKF. | Covariance head plus EKF measurement. Pedestrian, not vehicle. | https://arxiv.org/abs/2007.01867 | fetched |
| RoNIN | Yan and Herath (equal contribution), Furukawa. ICRA-era paper. arXiv 1905.12853. Site `http://ronin.cs.sfu.ca/`. | Heading-agnostic learned inertial odometry. Pedestrian phones. | https://arxiv.org/abs/1905.12853 | fetched (arXiv). ICRA 2020 venue: not re-opened on IEEE this session |
| RIDI | Yan, Shan, Furukawa. Velocity regression then corrected double integration. arXiv 1712.09004. | Shallow velocity / double-integration baseline. Pedestrian. | https://arxiv.org/abs/1712.09004 | fetched |
| IONet | Chen, Lu, Markham, Trigoni. Windowed RNN, polar displacement. arXiv 1802.02209. | Polar increment baseline. Pedestrian / trolley. | https://arxiv.org/abs/1802.02209 | fetched (arXiv). AAAI 2018 venue: not re-opened this session |
| RINS-W | Brossard, Barrau, Bonnabel. LSTM situation detector (zero velocity, no lateral slip) plus InEKF. arXiv 1903.02210. Paper quotes "20 m for a 21 km long trajectory" with a 10 deg/h gyro. | Vehicle ZUPT / no-lateral-slip detector into a filter. Car IMU, not phone MEMS. | https://arxiv.org/abs/1903.02210 | fetched |
| Chen and Pan survey | Chen, Pan. Deep Learning for Inertial Positioning: A Survey. arXiv 2303.03757. | Survey of learned inertial positioning. | https://arxiv.org/abs/2303.03757 | fetched (arXiv). IEEE T-ITS 2024 venue: not re-opened this session |
| Newson and Krumm | Hidden Markov Map Matching Through Noise and Sparseness. ACM SIGSPATIAL GIS 2009, Seattle, 4–6 November. Pages 336–343. DOI 10.1145/1653771.1653818. | HMM map matching under noise and sparse samples. | https://dl.acm.org/doi/10.1145/1653771.1653818 | fetched (ACM page). σ_z = 4.07 m and β estimator: **could not verify** (PDF body not opened) |
| Quddus, Ochieng, Noland | Current map-matching algorithms for transport applications. Transportation Research Part C, 2007. DOI 10.1016/j.trc.2007.05.002. | Map-matching review and integrity discussion. | https://doi.org/10.1016/j.trc.2007.05.002 | search snippet |
| Dissanayake, Sukkarieh, Nebot, Durrant-Whyte | The aiding of a low-cost strapdown inertial measurement unit using vehicle model constraints for land vehicle applications. IEEE Transactions on Robotics and Automation, vol. 17, no. 5, pp. 731–747, October 2001. | NHC / vehicle-model aiding of a strapdown IMU. | https://www-personal.acfr.usyd.edu.au/nebot/publications/gps_ins_constraints.pdf | fetched |
| Skog, Händel, Nilsson, Rantakokko | Zero-Velocity Detection: An Algorithm Evaluation. IEEE TBME vol. 57, no. 11, pp. 2657–2666, 2010 (cited as such on the Chen and Pan arXiv HTML). | ZUPT detector evaluation. | DOI commonly 10.1109/tbme.2010.2060723 | search snippet (venue/pages). Detector names SHOE / ARE / MV: **could not verify** (PDF not opened) |
| Solà | Quaternion kinematics for the error-state Kalman filter. arXiv 1711.02508. | Quaternion / error-state kinematics used by the ESKF. | https://arxiv.org/abs/1711.02508 | fetched |
| Groves | Principles of GNSS, Inertial, and Multisensor Integrated Navigation Systems. Artech House, 2nd ed., 2013. | Standard INS/GNSS textbook. | publisher listing only | search snippet. Integrity-monitoring chapter number: **could not verify** |

### TLIO covariance head (fetched)

From the TLIO abstract: the network "regresses 3D displacement estimates and its uncertainty" and the authors "show that our network ... can produce statistically consistent measurement and uncertainty to be used as the update step in the filter." The abstract does not use the words "diagonal covariance" or "NLL". Those implementation details are **could not verify** from the abstract alone.

### Recent phone / vehicle DR (2023–2026 and nearby)

| Work | What was seen | Why cite | URL | Status |
|---|---|---|---|---|
| DVSE-style phone speed | Xiao, Ren, Li. "An Inertial Sequence Learning Framework for Vehicle Speed Estimation via Smartphone IMU." arXiv 2505.18490. IMU sequence model supervised by GNSS. Pose-alignment network plus noise compensation. | Phone-in-vehicle speed from IMU. | https://arxiv.org/abs/2505.18490 | fetched |
| SenSpeed | Yu et al. Integrates accelerometer and corrects bias at reference points from turns, stops, and uneven surfaces. IEEE TMC DOI 10.1109/tmc.2015.2411270. PDF also opened from Rutgers/Stevens mirrors. INFOCOM 2014 origin is commonly listed. Quoted average errors 2.1 km/h real-time / 1.21 km/h offline on local roads. | Vibration and landmark speed without OBD. | https://doi.org/10.1109/tmc.2015.2411270 | search snippet plus PDF fetch of the TMC/INFOCOM paper |
| Vinande, Axelrad, Akos | Mounting-Angle Estimation for Personal Navigation Devices. Estimates roll, pitch, yaw of a PND from GPS velocity and accelerations. IEEE TVT DOI 10.1109/tvt.2009.2034667. Snippet: "pitch, roll, and yaw mounting angles are estimated to within 2° of truth when utilizing consumer-grade accelerometers." | Phone-to-vehicle mount angles. | https://doi.org/10.1109/tvt.2009.2034667 | search snippet |
| Khosravi et al. LUBE | Lower Upper Bound Estimation Method for Construction of Neural Network-Based Prediction Intervals. IEEE Transactions on Neural Networks, 2011, vol. 22, no. 3, pp. 337–346. DOI 10.1109/tnn.2010.2096824. Constructs intervals and discusses coverage probability. | PICP as an interval-coverage metric. | https://doi.org/10.1109/tnn.2010.2096824 | search snippet |
| TimesFM paper | Das, Kong, Sen, Zhou. A decoder-only foundation model for time-series forecasting. arXiv 2310.10688. ICML 2024 is stated on the Google Research GitHub README. | Desktop teacher citation only. Not a PS requirement. | https://arxiv.org/abs/2310.10688 | fetched |

Looked for and only partially found (see Could not verify): OdoNet (arXiv 2109.03811 HTML resolved to the wrong paper), AVNet (Satellite Navigation 2025), DeepOdo, CarSpeedNet, AirIMU, IEEE PLANS 2023 installation-angle paper, PADS spoofing papers, Kuleshov/Fenner/Ermon ICML 2018, Wahlström/Skog/Händel 2017 telematics survey, Bar-Shalom NIS chapter, Fu/Khider/van Diggelen ION GNSS+ 2020.

### Google Smartphone Decimeter Challenge (fetched / snippet)

ION page `https://www.ion.org/gnss/googlecompetition.cfm` (fetched): 3rd challenge 12 September 2023 to 23 May 2024. "Over 150 new traces containing raw GNSS measurements, sensor data, and precise ground truth." Teams use GNSS and IMU datasets from smartphones.

Kaggle 2022 overview (search snippet): `https://www.kaggle.com/competitions/smartphone-decimeter-2022`. Kaggle 2023 listing exists at `https://www.kaggle.com/competitions/smartphone-decimeter-2023`. Full Kaggle rules and licence text were not opened (pages are mostly scripted). Fu, Khider, van Diggelen ION GNSS+ 2020 dataset paper: **could not verify** (not opened).

### TimesFM naming (confirmed)

**TimesFM 3.0 is the current public release as of 2026-09-03.** The repo name "TimesFM 3" and checkpoint `google/timesfm-3.0-pytorch` are accurate today.

Fetched:

- GitHub `https://github.com/google-research/timesfm` README (`https://raw.githubusercontent.com/google-research/timesfm/master/README.md`): "Latest Model Version: TimesFM 3.0". Checkpoint `google/timesfm-3.0-pytorch`. Code Apache-2.0. Weights up to 2.5 remain Apache-2.0. **TimesFM 3.0 weights: `timesfm-non-commercial-license-v1.0`, non-commercial, non-production.**
- Google Research blog, 31 August 2026: `https://research.google/blog/timesfm-3-a-zero-shot-foundation-model-for-multivariate-forecasting/`. 330 million parameters. Native multivariate. Past-only and past-future covariates. Patch length 32. Single-pass horizon via contiguous patch masking. 9 quantiles (10th to 90th).
- Hugging Face `https://huggingface.co/google/timesfm-3.0-pytorch`: stacked mixing transformer, 20 layers, dim 1280, 16 heads, context patch 32, horizon patch 64.
- TimesFM 2.5 remains current for univariate / BigQuery. README: 200M parameters, context up to 16k, quantile horizon up to 1k. HF `google/timesfm-2.5-200m-pytorch` example uses `max_context=1024`, `max_horizon=256`.
- TimesFM 3.0 README examples use univariate horizon 12 and multivariate context 128 / horizon 24. A single published hard cap for 3.0 max context and max horizon was **not** found.

The official SIH26168 text does not mention TimesFM or foundation models. TimesFM is a team choice for optional desktop training, not a requirement response.

---

## E. NavIC / IRNSS and Android

### ISRO FAQ (fetched)

`https://www.isro.gov.in/FAQ_Navigation.html`

- NavIC is the operational name. IRNSS is the earlier name.
- GPS is not a generic word for satellite navigation. GNSS is.
- Q15/Q16: GAGAN is GPS Aided Geo Augmented Navigation, ISRO and AAI, SBAS for Indian airspace, integrity and safety-of-life.
- Q16 table, verbatim: GAGAN "Provides integrity information" and "Provides safety-of-life operation support". NavIC "Does not provide integrity information" and "Does not support safety-of-life operations".

### ISRO services page (fetched)

`https://www.isro.gov.in/SatelliteNavigationServices.html`

- Design: 7 satellites, 3 GEO (32.5°E, 83°E, 129.5°E), 4 IGSO.
- SPS and RS on L5 (1176.45 MHz) and S (2498.028 MHz). Coverage India plus 1500 km.
- Position better than 20 m (2σ), timing better than 50 ns (2σ).
- Verbatim: "A new civilian signal is being introduced in L1 band(1575.42 MHz). ... All forthcoming (2023 onwards) NavIC satellites will broadcast SPS signals in L1, L5 and S bands."
- ICDs named: L5 and S SIS ICD v1.1 August 2017. NavIC SPS ICD L1 v1.0 August 2023.

### NVS-01 (fetched)

`https://www.isro.gov.in/GSLV_F12_Landingpage.html`

> "GSLV-F12/NVS-01 mission is accomplished successfully on Monday, May 29, 2023." Lift-off 10:42 IST, Sriharikota. About 2232 kg. "This series incorporates L1 band signals additionally to widen the services." Indigenous atomic clock on NVS-01.

### Android APIs (fetched)

AOSP `GnssStatus.java` (`https://cs.android.com/android/platform/superproject/+/master:frameworks/base/location/java/android/location/GnssStatus.java`) and the developer reference snapshot:

- `CONSTELLATION_IRNSS = 7`. Added in API 29 (Android 10). Confirmed on `https://developer.android.com/reference/android/location/GnssStatus` and the API-29 diff `https://developer.android.com/sdk/api_diff/29/changes/android.location.GnssStatus`.
- `getCn0DbHz` added in API 24.
- `usedInFix(int)` reports whether that satellite was used in the most recent fix.
- IRNSS SVID range documented as 1 to 14.
- `GnssMeasurement.hasAutomaticGainControlLevelDb` / `getAutomaticGainControlLevelDb` exist and are deprecated in favour of `GnssMeasurementsEvent.getGnssAutomaticGainControls()` (API 33) (`https://developer.android.com/reference/android/location/GnssMeasurementsEvent`).

A phone that reports constellation 7 and a C/N0 is reporting OS/HAL visibility. That is not RAIM, not SBAS integrity, and not a NavIC integrity flag. ISRO already states NavIC SPS has no integrity.

### Chipsets (fetched / snippet)

Qualcomm announcement, 8 December 2023, WebWire `https://www.webwire.com/ViewPressRel.asp?aId=315220` (fetched): NavIC L1 support on select Qualcomm platforms from 2H 2024, commercial devices expected 1H 2025. Adds L1 to existing L5. Location Suite "supports up to seven satellite constellations concurrently".

MediaTek Dimensity / Helio NavIC lists: seen only on secondary blogs. **search snippet / could not verify** as a primary MediaTek page.

Indian government / DoT direction that all phones must support NavIC: seen in press as intent or later as remaining voluntary. A gazetted DoT mandate was **not** opened. **could not verify.**

### Phone versus integrity (short)

Android can log IRNSS space vehicles when the chipset HAL reports constellation 7. `usedInFix` and `getCn0DbHz` are OS reports. They are not an integrity service. ISRO states NavIC does not provide integrity and does not support safety-of-life. GAGAN is the GPS SBAS integrity path. L1 from NVS-01 (29 May 2023) onward is the consumer-band addition. Many phones still never surface IRNSS.

---

## F. Adjacent product behaviour

| Topic | What it says | URL | Status |
|---|---|---|---|
| Google Maps blue dot and circle | "The blue dot shows your location on the map. When Google Maps isn’t sure about your location, a light blue circle shows around the blue dot." "You could be anywhere within the light blue circle." "The smaller the circle, the more certain the app is about your location." Grey or missing dot: last known location. | https://support.google.com/maps/answer/2839911 | fetched |
| Google Maps heading beam | Android Maps replaced the direction arrow with a "shining blue beam". "The narrower the beam, the more accurate the direction. The wider the beam, the more likely it is that your phone’s compass is temporarily uncalibrated." Fix: figure-8 motion. 20 September 2016. | https://blog.google/products-and-platforms/products/maps/always-know-which-way-youre-headed-with/ | fetched |
| Google Maps / Waze tunnels | Android Google Maps can use Bluetooth tunnel beacons. Off by default. Works only where beacons are installed. Waze already supported the same idea. The Verge, 16 January 2024. | https://www.theverge.com/2024/1/16/24039896/google-maps-android-tunnels-bluetooth-beacons | fetched |
| Apple Maps in tunnels | Vendor documentation not opened. Quora and forum claims only. | n/a | could not verify |
| Pixel-specific tunnel DR | Not found as a vendor page. | n/a | could not verify |
| Apple Maps or Waze lost-signal copy | Official strings not opened. | n/a | could not verify |
| OsmAnd offline regions | Menu → Maps & Resources. Downloads / Local / Updates. Vector and raster. Standard map size shown before download. Free download counter versus paid contour/terrain/Wikipedia. Monthly updates. | https://osmand.net/docs/user/personal/maps | fetched |
| Organic Maps | Homepage timed out this session. | https://organicmaps.app/ | could not verify (timeout) |
| Garmin automotive DR | Current Garmin automotive marketing page not opened. An old StreetPilot 2650 manual (manymanuals) describes dead reckoning using an angular-rate sensor plus a professionally installed speed and reverse-light cable. That is vehicle-wired DR, not phone-only. | https://garmin.manymanuals.com/gps-receiver/streetpilot-2650/user-manual-48761/28 | search snippet. Official current Garmin auto DR page: could not verify |
| Survey controllers | Emlid glossary page timed out. Prior-session notes and search describe SINGLE / FLOAT / FIX. Not re-opened. | https://docs.emlid.com/emlid-studio/reference/glossary/ | could not verify (timeout) |
| Android Auto NavigationTemplate | Class exists at `androidx.car.app.navigation.model.NavigationTemplate`. Full distraction-guidelines page not opened. | https://developer.android.com/reference/androidx/car/app/navigation/model/NavigationTemplate | search snippet |
| 48 dp touch targets | Search snippet of `https://developer.android.com/guide/topics/ui/accessibility/apps`: targets at least 48 × 48 dp. Page timed out when fetched. | https://developer.android.com/guide/topics/ui/accessibility/apps | search snippet |
| Contrast | Search snippet of core-app-quality: 4.5:1 small text, 3:1 large. Page not opened. | https://developer.android.com/docs/quality-guidelines/core-app-quality | search snippet |

---

## G. Third-party usage policies

| Work | What it says | URL | Status |
|---|---|---|---|
| OpenFreeMap | Public instance is free, no API keys. Attribution required: "OpenFreeMap © OpenMapTiles Data from OpenStreetMap". MapLibre adds attribution automatically. Project license MIT. Map data is OSM. | https://openfreemap.org/ | fetched |
| MapLibre Native | BSD 2-Clause. Copyright MapLibre contributors, MapTiler, Mapbox. | https://raw.githubusercontent.com/maplibre/maplibre-native/main/LICENSE.md | fetched |
| Photon (komoot) | GitHub `komoot/photon`, about 3021 stars. Public API usage-policy page was not found. | https://github.com/komoot/photon | fetched (repo). Usage policy: could not verify |
| Nominatim | Max 1 request per second. Valid User-Agent or Referer. No autocomplete on the public API. No bulk or systematic scraping. ODbL share-alike. Apps must be able to switch service without an update. | https://operations.osmfoundation.org/policies/nominatim/ | fetched |
| OSRM demo | `router.project-osrm.org`. Non-commercial, reasonable use. Do not exceed 1 request per second. No SLA. | https://github.com/Project-OSRM/osrm-backend/wiki/Demo-server | fetched |
| OSM raster tiles | Must not bulk download, prefetch, or build offline archives from `tile.openstreetmap.org`. "Offline use is not permitted on tile.openstreetmap.org." Attribution required. Identify the app in User-Agent. | https://operations.osmfoundation.org/policies/tiles/ | fetched |
| ODbL 1.0 | OpenStreetMap database licence. Share-alike for produced works from the database. | https://opendatacommons.org/licenses/odbl/1-0/ | fetched |
| IBM Plex | SIL Open Font License 1.1. Reserved Font Name "Plex". | https://raw.githubusercontent.com/IBM/plex/master/LICENSE.txt | fetched |
| PMTiles | Specification public domain / CC0. Reference implementations BSD-3. | https://raw.githubusercontent.com/protomaps/PMTiles/main/LICENSE | fetched |
| Android SplashScreen | `androidx.core:core-splashscreen`. Search snippets describe `installSplashScreen()` before `super.onCreate()` and `Theme.SplashScreen`. Developer reference timed out. | https://developer.android.com/reference/androidx/core/splashscreen/SplashScreen | search snippet |

MapLibre Android PMTiles support is an implementation fact in this repo, not independently re-proven from MapLibre docs in this session. OSM tile policy wording above is the citation `docs/05` needed.

---

## H. Open-source or commercial comparators

None of the fetched research repos is a production Android phone-in-vehicle dead-reckoning navigator with an offline OSM pack.

| Name | What it is | Stars or date seen | URL | Status |
|---|---|---|---|---|
| mbrossar/ai-imu-dr | KITTI research code for AI-IMU Dead-Reckoning. Not a phone nav app. | GitHub page seen in search (paper links it) | https://github.com/mbrossar/ai-imu-dr | search snippet |
| CathIAS/TLIO (project cathias.github.io/TLIO) | Pedestrian headset research. | not counted | https://arxiv.org/abs/2007.01867 (project named in paper) | fetched (paper). Repo page not opened |
| Sachini/ronin | Pedestrian Python. GPL-3.0. | 409 stars and last push 2023-02-03 were seen in an earlier fetch this session | https://github.com/Sachini/ronin | fetched earlier this session |
| barbeau/gpstest | Android GNSS/GPS test app. Not vehicle DR. | 2,385 stars | https://github.com/barbeau/gpstest | fetched |
| google/gps-measurement-tools | GNSS analysis tools. Not a navigator. | 841 stars | https://github.com/google/gps-measurement-tools | fetched |
| OsmAnd | Offline maps and routing. Not strapdown vehicle DR. | not counted | https://osmand.net/docs/user/personal/maps | fetched (docs) |
| Organic Maps | Offline maps. Homepage timed out. | not counted | https://organicmaps.app/ | could not verify |
| kmazrolina/FOKZ_Nav_Tracker, OmerTariq-KAIST/DeepILS, aki1770-del/SNGNav | Seen in earlier search this session as indoor or IVI demos, not this product class. | not counted | GitHub | search snippet |

---

## Could not verify

Every item below was looked for in this session and not confirmed from a primary page.

1. Official 2026 screening or finale numeric weights.
2. Mandatory screening video (official upload is idea PDF only).
3. Whether recorded demos are accepted at the 2026 Grand Finale.
4. Whether "preliminary AI models" excludes a statistical or linear student inside a filter.
5. Live external FOG IMU as a finale hardware requirement.
6. IO-VNBD GitHub LICENSE file (none in the fetched README).
7. Data in Brief CC BY 4.0 line on the live ScienceDirect HTML (page blocked). The article is an Elsevier Data in Brief paper; CC BY is the usual series licence, not re-read on the journal HTML.
8. WhONet paper title, venue, and wheel-speed dependence.
9. Newson and Krumm numeric emission σ_z and β estimator (DOI and venue confirmed; PDF parameters not opened).
10. Skog 2010 detector names SHOE, ARE, MV from the PDF.
11. Groves 2013 integrity-monitoring chapter number.
12. Bar-Shalom, Li, Kirubarajan 2001 NIS chapter number.
13. Kuleshov, Fenner, Ermon, "Accurate Uncertainties for Deep Learning Using Calibrated Regression", ICML 2018 (arXiv 1805.10216 HTML resolved to an unrelated paper twice).
14. Wahlström, Skog, Händel, "Smartphone-Based Vehicle Telematics: A Ten-Year Anniversary", IEEE T-ITS 2017 (page not opened).
15. OdoNet (Tang et al., arXiv 2109.03811) full text (HTML resolved to the wrong paper).
16. AVNet (Satellite Navigation 2025) full text in this write-up pass.
17. DeepOdo, CarSpeedNet, AirIMU, IEEE PLANS 2023 installation-angle paper, PADS spoofing papers: not opened in the final pass.
18. Fu, Khider, van Diggelen, ION GNSS+ 2020 GSDC dataset paper.
19. Kaggle 2022 and 2023 licence text and a full IMU-column inventory (overview snippets only).
20. TimesFM 3.0 single numeric max-context and max-horizon caps.
21. Gazetted DoT / Government of India mandate that all phones must support NavIC.
22. Primary MediaTek NavIC announcement page.
23. Apple Maps vendor documentation for tunnel dead reckoning.
24. Pixel-specific tunnel dead-reckoning vendor page.
25. Official Apple Maps or Waze "lost signal" user-visible strings.
26. Current official Garmin automotive dead-reckoning product page (only an old StreetPilot 2650 manual snippet).
27. Emlid Flow / Studio glossary (timeout).
28. Organic Maps homepage (timeout).
29. Photon public API usage policy.
30. Android SplashScreen reference page (timeout). Full `developer.android.com` accessibility and contrast pages (timeout). Android Auto distraction-guidelines page.
31. Trimble Access horizontal RMS documentation.
32. RoNIN ICRA 2020 and IONet AAAI 2018 venue pages on IEEE/AAAI (arXiv texts fetched; venue stamps not re-opened).
33. Chen and Pan IEEE T-ITS 2024 issue stamp (arXiv fetched).
34. Whether `getAutomaticGainControlLevelDb` was added at a specific API below the deprecation-to-API-33 note.

---

## Resolved pending-verification tags

`docs/08_PRODUCT_DESIGN.md` had no `[pending verification]` tags on 2026-09-03 (checked at start and again before this file was finished).

`docs/07_RESEARCH_AND_ROADMAP.md` section 8 items, mapped to this file:

| # | Tag in docs/07 | Resolution |
|---|---|---|
| 1 | Official SIH26168 identity | Section A. Confirmed. Retrieval 2026-09-03. |
| 2 | Failure environments, jamming vs spoofing | Section A. Jamming named. Spoofing not named. |
| 3 | OBD-II wording | Section A. Hard prohibition of a vehicle-computer connection. |
| 4 | Lane-level verb | Section A. "maintaining lane-level accuracy". |
| 5 | OSM named | Section A. "Open Street Map" named. |
| 6 | External IMU, 200 Hz, FOG | Section A. FOG named, around 200 Hz. Interface requirement. Live FOG demo not stated. |
| 7 | Screening deliverable | Section A (plot + preliminary AI models) and section B (official idea PDF). Video not required by the 2026 guidelines. Whether a filter-plus-student counts as "AI" is not defined. |
| 8 | Module names | Section A. Six verbatim names. |
| 9 | 10 percent vs 5 m / 100 m | Section A. 10 percent is "must restrict". 5 m and 100 m are "for e.g." / "desired". |
| 10 | Magnetometer / compass input | Section A. Named. |
| 11 | Foundation-model wording in the PS | Section A. None. TimesFM is not a PS requirement. |
| 12 | SIH 2026 process, PPT, dates, teams per PS | Section B. Official PPT (6 content slides), guidelines, FAQ. 4 to 5 teams per PS "may be" selected. |
| 13 | 2026 evaluation criteria and weights | Section B. Criteria list fetched. Weights could not verify. |
| 14 | Finale live phone demo | Section B. Offline nodal centre, working prototype, PS says run on the smartphone. Recorded-demo rule could not verify. |
| 15 | IO-VNBD paper facts and Table A6 units | Section C. Authors, scale, rates, Table 5 units (km/hr). Table A6 is not a units table. |
| 16 | arXiv 2005.01701 and WhONet | Section C. 2005.01701 is the dataset paper. Related article is Applied Sciences 2021 11(3) 1270. WhONet could not verify. |
| 17 | Newson and Krumm DOI and parameters | Section D. DOI 10.1145/1653771.1653818 confirmed. σ and β could not verify. |
| 18 | Quddus 2007 DOI | Section D. DOI 10.1016/j.trc.2007.05.002, search snippet. |
| 19 | TLIO authors, venue, covariance | Section D. Authors and arXiv fetched. Displacement plus uncertainty confirmed. Diagonal NLL wording could not verify from the abstract. |
| 20 | Khosravi 2011 LUBE / PICP | Section D. Title and venue, search snippet. |
| 21 | Kuleshov ICML 2018 | Could not verify. |
| 22 | AI-IMU venue, year, KITTI | Section D. IEEE T-IV 2020, KITTI, 1.10% translational error quoted from the paper. Not phone numbers. |
| 23 | Bar-Shalom NIS chapter | Could not verify. |
| 24 | Vinande 2010 mount angles | Section D. IEEE TVT, DOI 10.1109/tvt.2009.2034667, search snippet. |
| 25 | Wahlström 2017 survey | Could not verify. |
| 26 | Groves chapter number | Could not verify. |
| 27 | Android GNSS APIs | Section E. `CONSTELLATION_IRNSS = 7` API 29. `getCn0DbHz` API 24. AGC deprecated on `GnssMeasurement`, collection on `GnssMeasurementsEvent` API 33. |
| 28 | Dissanayake 2001 volume and pages | Section D. T-RA 17(5):731–747, October 2001. Fetched. |
| 29 | Skog 2010 ZUPT | Section D. Venue/pages from citations. Detector names could not verify. |
| 30 | IONet, RIDI, RoNIN | Section D. arXiv IDs fetched. Some venue stamps not re-opened. |
| 31 | RINS-W and gyro denoise | Section D. Fetched. |
| 32 | OdoNet | Could not verify (wrong HTML). |
| 33 | Chen and Pan survey | Section D. arXiv fetched. T-ITS stamp not re-opened. |
| 34 | SenSpeed | Section D. Method confirmed from the paper PDF / TMC snippet. |
| 35 | AVNet and DVSE | DVSE arXiv 2505.18490 fetched. AVNet full text not in the final pass (could not verify). |
| 36 | GSDC paper and Kaggle licence | Section D. ION 2023–24 page fetched. 2020 paper and Kaggle licence could not verify. |
| 37 | TimesFM paper and 3.0 vs 2.5 | Section D. TimesFM 3.0 is current. HF path `google/timesfm-3.0-pytorch`. |
| 38 | Open-source comparators | Section H. None is a phone-in-vehicle Android DR navigator with offline OSM. |
| 39 | ISRO NavIC FAQ integrity | Section E. Fetched. NavIC does not provide integrity. GAGAN does. |
| 40 | MapLibre PMTiles and OSM tile policy | Section G. OSM tile policy fetched (no bulk / no offline from `tile.openstreetmap.org`). MapLibre Native BSD-2 fetched. MapLibre Android PMTiles support is a repo fact, not re-proven from MapLibre docs. |
