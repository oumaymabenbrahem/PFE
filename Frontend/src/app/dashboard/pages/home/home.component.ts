import { Component, AfterViewInit, ChangeDetectorRef } from '@angular/core';

@Component({
  selector: 'app-home',
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.scss']
})
export class HomeComponent implements AfterViewInit {

  constructor(private cdr: ChangeDetectorRef) {
    console.log('HomeComponent initialized');
  }

  ngAfterViewInit() {
    console.log('HomeComponent AfterViewInit');
    // Force change detection
    this.cdr.detectChanges();
    
    // Wait for DOM to be ready then initialize Swiper
    setTimeout(() => {
      this.initializeSwipers();
    }, 500);
  }

  initializeSwipers() {
    // Declare Swiper globally from CDN
    const Swiper = (window as any).Swiper;

    if (!Swiper) {
      console.error('Swiper is not loaded');
      return;
    }

    try {
      // Initialize main banner swiper
      const mainSwiper = new Swiper('.main-swiper', {
        slidesPerView: 1,
        spaceBetween: 20,
        loop: true,
        pagination: {
          el: '.swiper-pagination',
          clickable: true,
        },
        navigation: {
          nextEl: '.icon-arrow-right',
          prevEl: '.icon-arrow-left',
        },
        autoplay: {
          delay: 5000,
          disableOnInteraction: false,
        },
        breakpoints: {
          576: {
            slidesPerView: 2,
            spaceBetween: 15,
          },
          768: {
            slidesPerView: 2,
            spaceBetween: 20,
          },
          992: {
            slidesPerView: 3,
            spaceBetween: 20,
          },
          1200: {
            slidesPerView: 3,
            spaceBetween: 25,
          },
        },
      });

      console.log('Main swiper initialized:', mainSwiper);
    } catch (e) {
      console.error('Error initializing main swiper:', e);
    }
  }
}
