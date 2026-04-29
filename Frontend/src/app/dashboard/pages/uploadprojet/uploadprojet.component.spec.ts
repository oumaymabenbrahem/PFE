import { ComponentFixture, TestBed } from '@angular/core/testing';

import { UploadprojetComponent } from './uploadprojet.component';

describe('UploadprojetComponent', () => {
  let component: UploadprojetComponent;
  let fixture: ComponentFixture<UploadprojetComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      declarations: [UploadprojetComponent]
    });
    fixture = TestBed.createComponent(UploadprojetComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
