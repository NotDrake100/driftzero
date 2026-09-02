# Test fixtures

These files are schema and loader contracts. They are not a substitute for the full datasets.

| File | What it is | License |
|---|---|---|
| `io_vnbd_s_vta9_head.csv` | Header plus 3 rows from IO-VNBD `S-Vta9.csv` (Driver E, Vta09). Used so tests run without Git LFS. | Data in Brief article is CC BY 4.0. Cite Onyekpe et al., doi:10.1016/j.dib.2021.106885. Source: https://github.com/onyekpeu/IO-VNBD |
| `git-lfs-pointer.sample` | Git LFS pointer text in the public pointer format. Not payload data. | Git LFS spec |
| `euroc_imu0_format.csv` | Documented EuRoC ASL IMU header with 3 synthetic rows. Not an EuRoC sequence. | Format from Burri et al. EuRoC paper. Rows are DriftZero test numbers. |

Do not add TimesFM checkpoints here.
